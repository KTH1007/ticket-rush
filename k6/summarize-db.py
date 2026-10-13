#!/usr/bin/env python3
"""홀드와 결제까지 가는 사용자 흐름(k6/journey.js)의 결과를 마크다운 표 한 줄로 요약한다. 표준 라이브러리만 쓴다.

  header
  row <케이스 이름> <summary.json> <stats.tsv> <db.tsv> <앱 생존: yes|no>
"""
import json
import sys


def metric(summary, name, key, default=0.0):
    entry = summary.get("metrics", {}).get(name)
    if entry is None:
        return default
    for k in (key, "value", "values"):
        if k in entry and not isinstance(entry[k], dict):
            return entry[k]
    return default


def read_rows(path, width):
    rows = []
    try:
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == width:
                    rows.append(parts)
    except FileNotFoundError:
        pass
    return rows


def column_max(rows, role, index):
    values = [float(r[index]) for r in rows if r[1] == role]
    return max(values) if values else float("nan")


def header():
    print("| 케이스 | 줄 선 사용자 | 활성이 됨 | 홀드 시도 | 홀드 성공(201) | 충돌(409) | 홀드 그 밖의 실패 | 결제 성공(201) | 결제 그 밖의 실패 | 대기 시간 p50 / p95 | 앱 생존 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")


def row(case, summary_path, stats_path, db_path, alive):
    s = json.load(open(summary_path))
    c = lambda name: int(metric(s, name, "count"))
    wait = lambda key: metric(s, "journey_queue_wait_s", key, float("nan"))
    print(
        f"| {case} | {c('journey_registered'):,} | {c('journey_activated'):,} | {c('journey_hold_attempts'):,} | {c('journey_hold_201'):,} | "
        f"{c('journey_hold_conflict'):,} | {c('journey_hold_other'):,} | {c('journey_paid_201'):,} | {c('journey_pay_other'):,} | "
        f"{wait('med'):.1f}초 / {wait('p(95)'):.1f}초 | {'생존' if alive == 'yes' else '죽음'} |"
    )
    if "journey_register_wait_s" in s.get("metrics", {}) or "journey_register_503" in s.get("metrics", {}):
        rw = lambda key: metric(s, "journey_register_wait_s", key, float("nan"))
        rt = lambda key: metric(s, "journey_register_tries", key, float("nan"))
        print()
        print(f"- {case} 503과 재시도: 줄서기 503 {c('journey_register_503'):,}번(처음 누른 뒤 순번을 받기까지 p50 {rw('med'):.1f}초, p95 {rw('p(95)'):.1f}초, 최대 {rw('max'):.1f}초, "
              f"시도 횟수 평균 {rt('avg'):.2f}, 최대 {rt('max'):.0f}), 순번 조회 503 {c('journey_poll_503'):,}번, 홀드 503 {c('journey_hold_503'):,}번, 결제 503 {c('journey_pay_503'):,}번")
    dropped = s.get("metrics", {}).get("dropped_iterations")
    if dropped is not None:
        print()
        print(f"- {case} 부하기가 정한 속도를 못 맞춰 버린 반복(dropped_iterations, 앱이 느려 VU가 모자란 경우): {int(dropped.get('count', 0)):,}건")
    stats = read_rows(stats_path, 4)
    db = read_rows(db_path, 4)
    h = lambda name, key: metric(s, name, key, float("nan"))
    print()
    print(f"- {case} 응답 시간: 홀드 p95 {h('journey_hold_ms', 'p(95)'):.1f}ms, p99 {h('journey_hold_ms', 'p(99)'):.1f}ms (충돌 응답 p95 {h('journey_hold_conflict_ms', 'p(95)'):.1f}ms), "
          f"결제 p95 {h('journey_pay_ms', 'p(95)'):.1f}ms, p99 {h('journey_pay_ms', 'p(99)'):.1f}ms")
    active = max((float(r[1]) for r in db), default=float("nan"))
    pending = max((float(r[2]) for r in db), default=float("nan"))
    pg = max((float(r[3]) for r in db), default=float("nan"))
    print(f"- {case} DB: Hikari 사용 중 연결 최대 {active:.0f}개, 연결을 기다리는 스레드 최대 {pending:.0f}개, Postgres 동시 실행 쿼리 최대 {pg:.0f}개, "
          f"Postgres CPU 최대 {column_max(stats, 'postgres', 2):.0f}%, 앱 CPU 최대 {column_max(stats, 'app', 2):.0f}%, Redis CPU 최대 {column_max(stats, 'redis', 2):.0f}%")


def main(argv):
    command = argv[1]
    if command == "header":
        header()
    elif command == "row":
        row(argv[2], argv[3], argv[4], argv[5], argv[6])
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
