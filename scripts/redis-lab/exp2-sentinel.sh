#!/bin/bash
# 실험 2: Sentinel 페일오버. 마스터 1, 레플리카 2, Sentinel 3(quorum 2)을 띄우고 쓰기 클라이언트가 도는 중에 마스터를 docker kill 한다
# 사용: CONFIGS="5000:plain 30000:plain 5000:wait" REPS=3 ./exp2-sentinel.sh   (설정은 down-after-milliseconds:쓰기모드)
source "$(dirname "$0")/common.sh"
CONFIGS=${CONFIGS:-"30000:plain 5000:plain 5000:wait"}; REPS=${REPS:-3}; WRITERS=${WRITERS:-4}
OUTDIR=${OUTDIR:-/tmp/exp2}; mkdir -p "$OUTDIR"; : > "$OUTDIR/summary.jsonl"
trap lab_cleanup_all EXIT

run_rep() { # 설정 down-after 모드 회차
  local label=$1 down=$2 mode=$3 rep=$4 d="$OUTDIR/$1-$4" i
  mkdir -p "$d"
  lab_sentinel_cluster "$down" || return 1
  lab_rm tr-lab-client
  docker run --rm -i --name tr-lab-client --network $LAB_NET python:3-alpine python -u - "$SENTINELS" "$mode" "$WRITERS" < "$LAB_DIR/failover_client.py" > "$d/client.out" 2>&1 &
  local cpid=$!
  wait_for 120 "grep -q '^READY' '$d/client.out'" || { echo "클라이언트가 준비되지 않음" >&2; lab_rm tr-lab-client; return 1; }
  docker kill tr-lab-s-master > /dev/null
  local kill_at; kill_at=$(docker inspect -f '{{.State.FinishedAt}}' tr-lab-s-master)
  wait $cpid
  for i in 1 2 3; do docker logs -t tr-lab-s-sent$i > "$d/sent$i.log" 2>&1; done
  python3 "$LAB_DIR/sentinel_timeline.py" "$kill_at" "$d"/sent*.log > "$d/timeline.tsv"
  local client; client=$(grep '^{' "$d/client.out" | tail -1); client=${client:-null}
  echo "{\"config\":\"$label\",\"rep\":$rep,\"client\":$client}" >> "$OUTDIR/summary.jsonl"
  log "$label 회차$rep: $client"
  sed 's/^/    /' "$d/timeline.tsv"
}

for cfg in $CONFIGS; do
  down=${cfg%%:*}; mode=${cfg##*:}
  for ((r = 1; r <= REPS; r++)); do run_rep "down${down}-${mode}" "$down" "$mode" "$r"; done
done
