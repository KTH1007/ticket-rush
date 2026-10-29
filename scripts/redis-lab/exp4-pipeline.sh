#!/bin/bash
# 실험 4: redis-benchmark로 파이프라인 유무에 따른 처리량. 서버는 CPU 1코어로 제한하고(측정 스택과 같음) 벤치마크는 다른 컨테이너에서 건다
source "$(dirname "$0")/common.sh"
NAME=tr-lab-pipe; REQS=${REQS:-1000000}; REPS=${REPS:-3}
trap lab_cleanup_all EXIT
lab_net_up; lab_rm $NAME
docker run -d --name $NAME --network $LAB_NET --cpus 1 "$LAB_REDIS_IMAGE" redis-server --save "" --appendonly no > /dev/null
for i in $(seq 1 50); do [ "$(rcli $NAME PING 2> /dev/null)" = PONG ] && break; sleep 0.2; done
log "Redis $(rcli $NAME INFO server | grep -m1 redis_version | tr -d '\r'), CPU 1코어, 요청 ${REQS}개, 연결 50개"
log "지연은 묶음을 보낸 뒤 첫 응답까지의 시간이며 같은 묶음의 모든 명령에 같은 값이 기록됨(-P가 크면 마지막 명령의 완료는 이보다 늦음)"
echo "파이프라인,회차,SET rps,SET p50 ms,SET p95 ms,GET rps,GET p50 ms,GET p95 ms"
for p in 1 4 16 64; do
  for ((r = 1; r <= REPS; r++)); do
    out=$(docker run --rm --network $LAB_NET "$LAB_REDIS_IMAGE" redis-benchmark -h $NAME -q -n "$REQS" -c 50 -P "$p" -t set,get --csv 2>&1)
    set_rps=$(echo "$out" | grep '"SET"' | cut -d, -f2 | tr -d '"'); get_rps=$(echo "$out" | grep '"GET"' | cut -d, -f2 | tr -d '"')
    # --csv 열 순서: test, rps, avg, min, p50, p95, p99, max
    set_p50=$(echo "$out" | grep '"SET"' | cut -d, -f5 | tr -d '"'); set_p95=$(echo "$out" | grep '"SET"' | cut -d, -f6 | tr -d '"')
    get_p50=$(echo "$out" | grep '"GET"' | cut -d, -f5 | tr -d '"'); get_p95=$(echo "$out" | grep '"GET"' | cut -d, -f6 | tr -d '"')
    echo "$p,$r,$set_rps,$set_p50,$set_p95,$get_rps,$get_p50,$get_p95"
  done
done
