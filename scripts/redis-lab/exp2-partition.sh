#!/bin/bash
# 실험 2(분단): 마스터가 살아 있는 채로 레플리카와 Sentinel에서만 끊긴다. 옛 마스터가 받은 쓰기가 사라지는지, min-replicas-to-write와 WAIT의 효과
# 사용: CONFIGS="plain minrep wait" REPS=3 ./exp2-partition.sh
source "$(dirname "$0")/common.sh"
CONFIGS=${CONFIGS:-"plain minrep wait"}; REPS=${REPS:-3}; WRITERS=${WRITERS:-4}; RATE=${RATE:-2000}
PART_S=${PART_S:-20}; RUN_S=${RUN_S:-40}
OUTDIR=${OUTDIR:-/tmp/exp2p}; mkdir -p "$OUTDIR"; : > "$OUTDIR/summary.jsonl"
trap 'lab_cleanup_all; docker rmi -f tr-lab-nettools > /dev/null 2>&1 || true' EXIT
docker image inspect tr-lab-nettools > /dev/null 2>&1 || docker build -q -t tr-lab-nettools - <<'DOCKER' > /dev/null
FROM alpine:latest
RUN apk add --no-cache iptables
DOCKER

run_rep() { # 설정 회차
  local cfg=$1 rep=$2 d="$OUTDIR/$1-$2" mode=plain extra=() i
  mkdir -p "$d"
  case $cfg in
    minrep) extra=(--min-replicas-to-write 1 --min-replicas-max-lag 3) ;;
    wait) mode=wait ;;
  esac
  lab_sentinel_cluster 5000 "${extra[@]}" || return 1
  lab_rm tr-lab-client
  docker run --rm -i --name tr-lab-client --network $LAB_NET python:3-alpine python -u - "$SENTINELS" "$mode" "$WRITERS" "$RUN_S" "$RATE" < "$LAB_DIR/partition_client.py" > "$d/client.out" 2>&1 &
  local cpid=$!
  wait_for 120 "grep -q '^READY' '$d/client.out'" || { echo "클라이언트가 준비되지 않음" >&2; lab_rm tr-lab-client; return 1; }
  sleep 2
  local part_at heal_at
  part_at=$(lab_partition "$R1" "$R2" $(echo "$SENTINELS" | tr ',' ' ')) || { echo "분단을 걸지 못함(iptables 실패)" >&2; lab_rm tr-lab-client; return 1; }
  part_at=$(echo "$part_at" | tail -1)
  sleep "$PART_S"
  heal_at=$(lab_heal) || { echo "분단을 풀지 못함(iptables 실패)" >&2; lab_rm tr-lab-client; return 1; }
  heal_at=$(echo "$heal_at" | tail -1)
  wait $cpid
  for i in 1 2 3; do docker logs -t tr-lab-s-sent$i > "$d/sent$i.log" 2>&1; done
  python3 "$LAB_DIR/sentinel_timeline.py" "$part_at" "$d"/sent*.log > "$d/timeline.tsv"
  local client; client=$(grep '^{' "$d/client.out" | tail -1); client=${client:-null}
  echo "{\"config\":\"$cfg\",\"rep\":$rep,\"part_at\":\"$part_at\",\"heal_at\":\"$heal_at\",\"client\":$client}" >> "$OUTDIR/summary.jsonl"
  log "$cfg 회차$rep 끝"
  sed 's/^/    /' "$d/timeline.tsv"
}
for cfg in $CONFIGS; do for ((r = 1; r <= REPS; r++)); do run_rep "$cfg" "$r"; done; done
