#!/bin/bash
# 병목 분석 2: 승격 스크립트 길이. 대기자 50만 명에서 승격 배치 크기별로 스크립트 한 번의 실행 시간과, 그동안 다른 연결의 PING 지연을 잰다
# 스크립트는 끊김 없이 실행되므로 길수록 다른 요청이 그만큼 기다린다. 승격 속도를 올릴 때 한계가 되는 지점을 찾는다
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
N=${N:-500000}; REPS=${REPS:-3}; SIZES=${SIZES:-"100 500 1000 5000 20000"}
SC=${SC:-$(cd "$LAB_DIR/../.." && pwd)/src/main/resources/scripts}
trap lab_cleanup_all EXIT
log "승격 스크립트(queue-promote.lua) 실제 코드, 대기자 ${N}명, AOF everysec, CPU ${CPUS:-2}코어"
for size in $SIZES; do for ((rep = 1; rep <= REPS; rep++)); do
  set_mode aof-everysec; ARGS+=(--lazyfree-lazy-user-del yes --lazyfree-lazy-expire yes)
  aof_up tr-lab-bench "--cpus ${CPUS:-2}" "${ARGS[@]}" || exit 1
  docker cp "$LAB_DIR/gen-queue.awk" tr-lab-bench:/tmp/gen-queue.awk
  docker exec tr-lab-bench sh -c "awk -v n=$N -f /tmp/gen-queue.awk | redis-cli --pipe" > /dev/null 2>&1
  rcli tr-lab-bench SET queue:1:seq "$N" > /dev/null; rcli tr-lab-bench SADD queue:activeEventIds 1 > /dev/null
  KEYS='["queue:1:waiting","queue:1:lastPromotedSeq","queue:activeEventIds","queue:1:seq"]'
  ARGS_J="[$size,120,1,1000000000,5,\"queue:promotionBudget:\",86400]"
  out=$(docker run --rm -i --network $LAB_NET -e LUA="$(cat "$SC/queue-promote.lua")" -e KEYS_JSON="$KEYS" -e ARGS_JSON="$ARGS_J" python:3-alpine python -u - tr-lab-bench 3 3 < "$LAB_DIR/script_probe.py" | tail -1)
  echo "$size,$rep,$out"
  lab_rm tr-lab-bench
done; done
