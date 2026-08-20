"""PR 댓글(`/gemini-review`)로 부르는 온디맨드 코드 리뷰어.

CodeRabbit과 역할을 나눈다. CodeRabbit은 자동 트리거라 무료 티어 쿼터가
PR 빈도에 밀려 소진되는 문제가 있었다(#38 배경). 이건 원할 때만 부르고,
`.coderabbit.yml`의 path_instructions를 그대로 읽어서 같은 도메인 규칙을
재사용한다. 규칙 원본은 하나로 유지한다.

증분 리뷰: 마지막으로 리뷰한 커밋 SHA를 이 스크립트가 남기는 리뷰 본문에
마커(REVIEWED_SHA_MARKER)로 남겨두고, 다음 실행 때 그 마커를 읽어 그 이후
diff만 본다. 별도 DB 없이 PR 자체를 상태 저장소로 쓰는 방식이다.

중복 지적 방지: 기존 인라인 리뷰 코멘트의 (path, line)을 모아 프롬프트에
"이미 지적함, 다시 지적하지 마라"로 넘기고, 혹시 모델이 어겨도 최종적으로
한 번 더 걸러낸다.
"""

from __future__ import annotations

import collections
import fnmatch
import json
import os
import re
import subprocess
import sys

import requests
import yaml

GITHUB_API = "https://api.github.com"
GEMINI_MODEL = "gemini-3.1-pro-preview"
GEMINI_API = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
BOT_LOGIN = "github-actions[bot]"
REVIEWED_SHA_MARKER = re.compile(r"<!-- gemini-review: reviewed-sha=([0-9a-f]{40}) -->")

REPO = os.environ["REPO"]
PR_NUMBER = os.environ["PR_NUMBER"]
GH_TOKEN = os.environ["GH_TOKEN"]
GEMINI_API_KEY = os.environ["GEMINI_API_KEY"]

gh_session = requests.Session()
gh_session.headers.update(
    {
        "Authorization": f"Bearer {GH_TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
)


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


def gh_get(path: str) -> object:
    resp = gh_session.get(f"{GITHUB_API}{path}")
    resp.raise_for_status()
    return resp.json()


def fetch_pr() -> dict:
    return gh_get(f"/repos/{REPO}/pulls/{PR_NUMBER}")


def fetch_last_reviewed_sha() -> str | None:
    # 최신 리뷰부터 훑어서 우리가 남긴 마커를 찾는다.
    reviews = gh_get(f"/repos/{REPO}/pulls/{PR_NUMBER}/reviews")
    for review in reversed(reviews):
        if review["user"]["login"] != BOT_LOGIN:
            continue
        match = REVIEWED_SHA_MARKER.search(review.get("body") or "")
        if match:
            return match.group(1)
    return None


def fetch_already_flagged() -> set[tuple[str, int]]:
    comments = gh_get(f"/repos/{REPO}/pulls/{PR_NUMBER}/comments")
    flagged = set()
    for comment in comments:
        if comment["user"]["login"] != BOT_LOGIN:
            continue
        line = comment.get("line") or comment.get("original_line")
        if line is not None:
            flagged.add((comment["path"], line))
    return flagged


def compute_diff(base_ref: str, last_reviewed_sha: str | None) -> tuple[str, list[str]]:
    if last_reviewed_sha:
        diff_range = f"{last_reviewed_sha}..HEAD"
    else:
        run("git", "fetch", "origin", base_ref, "--depth=1")
        diff_range = f"origin/{base_ref}...HEAD"

    diff_text = run("git", "diff", diff_range)
    changed_files = [f for f in run("git", "diff", "--name-only", diff_range).splitlines() if f]
    return diff_text, changed_files


def valid_lines_by_file(diff_text: str) -> dict[str, set[int]]:
    """유니파이드 diff에서, 새 파일 버전(diff의 + 쪽) 기준으로 실제 존재하는
    줄 번호만 파일별로 뽑는다.

    GitHub 리뷰 API는 diff에 나타나지 않는 줄에 코멘트를 달려고 하면 리뷰
    등록 요청 전체를 거부한다(하나만 잘못돼도 나머지 정상 지적까지 다
    날아간다). 모델이 존재하지 않는 줄 번호를 지어내는 경우를 제출 전에
    걸러내기 위한 방어선이다.
    """
    valid: dict[str, set[int]] = collections.defaultdict(set)
    current_file = None
    new_line = None
    hunk_header = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")

    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            current_file = line[len("+++ b/") :]
            new_line = None
            continue

        match = hunk_header.match(line)
        if match:
            new_line = int(match.group(1))
            continue

        if new_line is None or current_file is None:
            continue

        if line.startswith("+") or line.startswith(" "):
            valid[current_file].add(new_line)
            new_line += 1
        # "-"로 시작하는 삭제된 줄은 새 버전에 없으므로 카운터를 올리지 않는다.
        # diff --git/index/--- a/ 같은 헤더 줄도 여기 안 걸린다.

    return valid


def load_path_instructions() -> list[dict]:
    with open(".coderabbit.yml", encoding="utf-8") as f:
        config = yaml.safe_load(f)
    return config.get("reviews", {}).get("path_instructions", [])


def matching_instructions(changed_files: list[str], path_instructions: list[dict]) -> str:
    matched = []
    for entry in path_instructions:
        pattern = entry["path"]
        if any(fnmatch.fnmatch(f, pattern) for f in changed_files):
            matched.append(f"### {pattern}\n{entry['instructions'].strip()}")
    return "\n\n".join(matched)


def build_prompt(diff_text: str, domain_rules: str, already_flagged: set[tuple[str, int]]) -> str:
    flagged_text = (
        "\n".join(f"- {path}:{line}" for path, line in sorted(already_flagged)) or "(없음)"
    )
    return f"""너는 이 프로젝트(ticket-rush, 콘서트 티켓 예매 시스템)의 코드 리뷰어다.
아래 diff를 검토하고, 실제 버그나 이 프로젝트의 설계 규칙 위반만 지적해라.
사소한 스타일 지적은 하지 마라(ktlint/detekt가 이미 잡는다).

# 이 프로젝트의 설계 규칙 (위반만 지적, 아래 규칙에 없는 일반론적 제안은 하지 마라)
{domain_rules or "(이 diff에 해당하는 규칙 없음)"}

# 이미 지적한 위치 (다시 지적하지 마라)
{flagged_text}

# diff
```diff
{diff_text}
```

각 지적은 diff에 실제로 나타나는 파일 경로와, 그 파일의 새 버전(diff의 + 쪽) 기준 줄 번호를 정확히 써라.
지적할 게 없으면 findings를 빈 배열로 반환해라.
"""


def call_gemini(prompt: str) -> list[dict]:
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseSchema": {
                "type": "OBJECT",
                "properties": {
                    "findings": {
                        "type": "ARRAY",
                        "items": {
                            "type": "OBJECT",
                            "properties": {
                                "path": {"type": "STRING"},
                                "line": {"type": "INTEGER"},
                                "body": {"type": "STRING"},
                            },
                            "required": ["path", "line", "body"],
                        },
                    }
                },
                "required": ["findings"],
            },
        },
    }
    resp = requests.post(
        GEMINI_API,
        headers={"x-goog-api-key": GEMINI_API_KEY, "Content-Type": "application/json"},
        json=body,
        timeout=120,
    )
    resp.raise_for_status()
    text = resp.json()["candidates"][0]["content"]["parts"][0]["text"]
    return json.loads(text)["findings"]


def submit_review(findings: list[dict], head_sha: str) -> None:
    comments = [{"path": f["path"], "line": f["line"], "side": "RIGHT", "body": f["body"]} for f in findings]

    if comments:
        summary = f"Gemini 리뷰: {len(comments)}건 지적함."
    else:
        summary = "Gemini 리뷰: 지적 사항 없음."
    summary += f"\n\n<!-- gemini-review: reviewed-sha={head_sha} -->"

    resp = gh_session.post(
        f"{GITHUB_API}/repos/{REPO}/pulls/{PR_NUMBER}/reviews",
        json={"commit_id": head_sha, "body": summary, "event": "COMMENT", "comments": comments},
    )
    resp.raise_for_status()


def main() -> None:
    pr = fetch_pr()
    base_ref = pr["base"]["ref"]
    head_sha = run("git", "rev-parse", "HEAD")

    last_reviewed_sha = fetch_last_reviewed_sha()
    already_flagged = fetch_already_flagged()

    diff_text, changed_files = compute_diff(base_ref, last_reviewed_sha)
    if not diff_text.strip():
        print("리뷰할 변경 사항이 없습니다.")
        return

    path_instructions = load_path_instructions()
    domain_rules = matching_instructions(changed_files, path_instructions)

    prompt = build_prompt(diff_text, domain_rules, already_flagged)
    findings = call_gemini(prompt)

    valid_lines = valid_lines_by_file(diff_text)
    before = len(findings)
    findings = [
        f
        for f in findings
        # 모델이 프롬프트 지시를 어기고 이미 지적한 위치를 또 지적했을 경우의 최종 방어선.
        if (f["path"], f["line"]) not in already_flagged
        # diff에 실제로 없는 줄이면 리뷰 등록 자체가 통째로 거부되니 여기서 미리 뺀다.
        and f["line"] in valid_lines.get(f["path"], set())
    ]
    dropped = before - len(findings)
    if dropped:
        print(f"경고: 유효하지 않은 지적 {dropped}건 제외함 (이미 지적됨 또는 diff에 없는 줄)")

    submit_review(findings, head_sha)
    print(f"리뷰 등록 완료: {len(findings)}건 지적")


if __name__ == "__main__":
    try:
        main()
    except requests.HTTPError as e:
        print(f"HTTP 에러: {e.response.status_code} {e.response.text}", file=sys.stderr)
        raise
