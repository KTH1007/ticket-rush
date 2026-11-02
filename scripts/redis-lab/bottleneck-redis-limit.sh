#!/bin/bash
# 병목 분석 1: Redis 단독 한계. 앱 없이 redis-benchmark가 실제 등록, 상태 조회 스크립트를 EVALSHA로 보내
# 한 코어(--cpus 1)의 처리량 상한과 그때 서버 안쪽(메인 스레드 CPU, 호출당 시간, PING 지연)을 본다
# 환경 변수: WORKLOADS(register status), MODES(aof off), REPS, REQ_REGISTER, REQ_STATUS, CLIENTS, THREADS, OUTDIR
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
SC=${SC:-$(cd "$LAB_DIR/../.." && pwd)/src/main/resources/scripts}
WORKLOADS=${WORKLOADS:-"register status"}; MODES=${MODES:-"aof off"}; REPS=${REPS:-3}
REQ_REGISTER=${REQ_REGISTER:-300000}; REQ_STATUS=${REQ_STATUS:-1000000}; CLIENTS=${CLIENTS:-100}; THREADS=${THREADS:-4}
OUTDIR=${OUTDIR:-/tmp/redis-lab-bottleneck}; mkdir -p "$OUTDIR"; N_TOKENS=500000
trap 'docker rm -fv tr-lab-sampler > /dev/null 2>&1; lab_cleanup_all' EXIT

host_idle() { top -l 2 -n 0 -s 1 2> /dev/null | grep "^CPU usage" | tail -1 | sed 's/.* \([0-9.]*\)% idle.*/\1/'; }

redis_up() { # 모드 -> tr-lab-bench
  case $1 in
    aof) set_mode aof-everysec ;;
    aofno) set_mode aof-no ;;
    off) set_mode off ;;
  esac
  ARGS+=(--lazyfree-lazy-user-del yes --lazyfree-lazy-expire yes)
  # shellcheck disable=SC2206
  [ -n "${EXTRA:-}" ] && ARGS+=($EXTRA)
  aof_up tr-lab-bench "--cpus ${CPUS:-1}" "${ARGS[@]}"
}

preload_tokens() {
  docker exec tr-lab-bench sh -c "awk -v n=$N_TOKENS 'BEGIN{for(i=0;i<n;i++){k=sprintf(\"queue:1:token:%012d\",i);v=i+1;printf \"*5\r\n\$3\r\nSET\r\n\$%d\r\n%s\r\n\$%d\r\n%d\r\n\$2\r\nEX\r\n\$4\r\n7200\r\n\",length(k),k,length(v \"\"),v}}' | redis-cli --pipe" > /dev/null 2>&1
  rcli tr-lab-bench SET queue:1:lastPromotedSeq 0 > /dev/null
}

run_one() { # 워크로드 모드 회차
  local wl=$1 mode=$2 rep=$3 sha idle raw
  redis_up "$mode" || return 1
  rcli tr-lab-bench CONFIG SET slowlog-log-slower-than 1000 > /dev/null
  rcli tr-lab-bench CONFIG SET latency-monitor-threshold 10 > /dev/null
  if [ "$wl" = register ]; then
    sha=$(rcli tr-lab-bench SCRIPT LOAD "$(cat "$SC/queue-register.lua")")
    rcli tr-lab-bench SET queue:1:opensAt 0 > /dev/null; rcli tr-lab-bench SET queue:1:startsAt 253402300799000 > /dev/null
    BENCH=(EVALSHA "$sha" 9 queue:1:seq queue:1:waiting "queue:1:token:__rand_int__" queue:activeEventIds queue:1:opensAt queue:1:startsAt queue:1:register-ip:1.2.3.4 queue:1:lastPromotedSeq queue:1:cleaned "__rand_int__" 900 1 600 "" "" 1000000000 60 1000000000)
    REQ=$REQ_REGISTER; KEYSPACE=100000000000
  else
    preload_tokens
    sha=$(rcli tr-lab-bench SCRIPT LOAD "$(cat "$SC/queue-status.lua")")
    BENCH=(EVALSHA "$sha" 4 "queue:1:token:__rand_int__" "queue:1:active:__rand_int__" queue:1:lastPromotedSeq queue:1:cleaned 900)
    REQ=$REQ_STATUS; KEYSPACE=$N_TOKENS
  fi
  idle=$(host_idle)
  docker rm -fv tr-lab-sampler > /dev/null 2>&1
  docker run -d --name tr-lab-sampler --network $LAB_NET -v "$LAB_DIR/sampler.py:/s.py:ro" python:3-alpine python -u /s.py tr-lab-bench > /dev/null
  sleep 3
  raw=$(docker run --rm --network $LAB_NET "$LAB_REDIS_IMAGE" redis-benchmark -h tr-lab-bench -c "$CLIENTS" --threads "$THREADS" -n "$REQ" -r "$KEYSPACE" --csv "${BENCH[@]}" 2>&1 | tail -1)
  sleep 1
  docker stop -t 2 tr-lab-sampler > /dev/null; docker logs tr-lab-sampler 2> /dev/null | grep '^{' > "$OUTDIR/$wl-$mode-$rep.samples"; docker rm -fv tr-lab-sampler > /dev/null
  echo "$raw" > "$OUTDIR/$wl-$mode-$rep.csv"
  rcli tr-lab-bench SLOWLOG LEN > "$OUTDIR/$wl-$mode-$rep.slowlen"
  rcli tr-lab-bench SLOWLOG GET 3 | tr '\n' ' ' | head -c 500 > "$OUTDIR/$wl-$mode-$rep.slowtop"
  rcli tr-lab-bench LATENCY LATEST | tr '\n' ' ' > "$OUTDIR/$wl-$mode-$rep.latency"
  rcli tr-lab-bench INFO persistence | grep -E '^(aof_delayed_fsync|aof_rewrites|aof_last_write_status|aof_rewrite_in_progress|aof_last_rewrite_time_sec):' | tr -d '\r' | tr '\n' ' ' > "$OUTDIR/$wl-$mode-$rep.persist"
  echo "$idle" > "$OUTDIR/$wl-$mode-$rep.idle"
  docker exec tr-lab-bench cat /sys/fs/cgroup/cpu.stat 2> /dev/null | tr '\n' ' ' > "$OUTDIR/$wl-$mode-$rep.cpustat"
  lab_rm tr-lab-bench
}

for wl in $WORKLOADS; do for mode in $MODES; do for ((r = 1; r <= REPS; r++)); do
  log "$wl $mode 회차$r"; run_one "$wl" "$mode" "$r"
done; done; done
log "끝: $OUTDIR"
