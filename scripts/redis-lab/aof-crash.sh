#!/bin/bash
# AOF 실험 1: 프로세스를 SIGKILL로 죽였다 되살렸을 때 확인받은 쓰기가 몇 건 사라지나 (모드별)
# 한계: 프로세스 crash만 재현한다. OS 장애나 전원 장애는 재현하지 못하므로 appendfsync 차이가 드러나지 않을 수 있다
# 환경 변수: MODES, REPS, WRITERS, OUTDIR
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
MODES=${MODES:-"off aof-no aof-everysec aof-always rdb"}
REPS=${REPS:-3}; WRITERS=${WRITERS:-4}
OUTDIR=${OUTDIR:-/tmp/redis-lab-aof-crash}; mkdir -p "$OUTDIR"; : > "$OUTDIR/summary.jsonl"
trap lab_cleanup_all EXIT

run_one() { # 모드 회차 kill까지_최소초 kill까지_추가범위초
  local mode=$1 rep=$2 kmin=$3 krange=$4 ka client pre load cpid
  set_mode "$mode"; aof_up tr-lab-aof "--cpus 1 --memory 1g" "${ARGS[@]}" || return 1
  lab_rm tr-lab-client
  docker run --rm -i --name tr-lab-client --network $LAB_NET python:3-alpine python -u - tr-lab-aof "$WRITERS" < "$LAB_DIR/crash_client.py" > "$OUTDIR/$mode-$rep.client" 2>&1 &
  cpid=$!
  wait_for 120 "grep -q '^READY' '$OUTDIR/$mode-$rep.client'" || { echo "클라이언트가 준비되지 않음" >&2; lab_rm tr-lab-client; return 1; }
  ka=$((kmin + RANDOM % krange)); sleep "$ka"
  pre=$(rcli tr-lab-aof INFO persistence | grep -E '^(aof_enabled|aof_delayed_fsync|rdb_changes_since_last_save|rdb_last_save_time):' | tr -d '\r' | paste -sd' ' -)
  docker kill tr-lab-aof > /dev/null; docker start tr-lab-aof > /dev/null
  wait $cpid
  load=$(load_line tr-lab-aof)
  client=$(grep '^{' "$OUTDIR/$mode-$rep.client" | tail -1); client=${client:-null}
  echo "{\"mode\":\"$mode\",\"rep\":$rep,\"kill_after_s\":$((ka + 3)),\"pre\":\"$pre\",\"load\":\"$load\",\"client\":$client}" >> "$OUTDIR/summary.jsonl"
  log "$mode 회차$rep: $client"
  lab_rm tr-lab-aof tr-lab-client
}

for mode in $MODES; do
  for ((r = 1; r <= REPS; r++)); do
    if [ "$mode" = rdb ]; then run_one "$mode" "$r" 72 6; else run_one "$mode" "$r" 4 4; fi
  done
done
log "요약: $OUTDIR/summary.jsonl"
