#!/bin/bash
# 병목 분석 3: 같은 초당 조회 속도에서 대기자 규모(토큰 키 수)만 바꿨을 때 Redis CPU가 달라지는지, 어디서 쓰는지
#  메인 스레드 CPU(INFO cpu), 명령 실행이 차지한 CPU(commandstats), 컨테이너 전체 CPU(cgroup, docker stats가 보여주는 값과 같은 출처)
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
SC=${SC:-$(cd "$LAB_DIR/../.." && pwd)/src/main/resources/scripts}
SIZES=${SIZES:-"100000 500000"}; RATE=${RATE:-5000}; SECONDS_RUN=${SECONDS_RUN:-30}; REPS=${REPS:-2}
OUTDIR=${OUTDIR:-/tmp/redis-lab-bottleneck-rate}; mkdir -p "$OUTDIR"
trap 'docker rm -fv tr-lab-sampler > /dev/null 2>&1; lab_cleanup_all' EXIT
usage() { docker exec tr-lab-bench cat /sys/fs/cgroup/cpu.stat | awk '/^usage_usec/{print $2}'; }
for n in $SIZES; do for ((rep = 1; rep <= REPS; rep++)); do
  set_mode aof-everysec; ARGS+=(--lazyfree-lazy-user-del yes --lazyfree-lazy-expire yes)
  aof_up tr-lab-bench "--cpus ${CPUS:-2}" "${ARGS[@]}" || exit 1
  docker exec tr-lab-bench sh -c "awk -v n=$n 'BEGIN{for(i=0;i<n;i++){k=sprintf(\"queue:1:token:%012d\",i);v=i+1;printf \"*5\r\n\$3\r\nSET\r\n\$%d\r\n%s\r\n\$%d\r\n%d\r\n\$2\r\nEX\r\n\$4\r\n7200\r\n\",length(k),k,length(v \"\"),v}}' | redis-cli --pipe" > /dev/null 2>&1
  rcli tr-lab-bench SET queue:1:lastPromotedSeq 0 > /dev/null
  sha=$(rcli tr-lab-bench SCRIPT LOAD "$(cat "$SC/queue-status.lua")")
  sleep 5   # 적재 직후의 AOF 쓰기와 rewrite가 가라앉게
  docker rm -fv tr-lab-sampler > /dev/null 2>&1
  docker run -d --name tr-lab-sampler --network $LAB_NET -v "$LAB_DIR/sampler.py:/s.py:ro" python:3-alpine python -u /s.py tr-lab-bench > /dev/null
  sleep 2; u0=$(usage); t0=$(python3 -c 'import time;print(time.time())')
  out=$(docker run --rm --network $LAB_NET -v "$LAB_DIR/rate_status.py:/r.py:ro" python:3-alpine python -u /r.py tr-lab-bench "$n" "$RATE" "$SECONDS_RUN" "$sha")
  u1=$(usage); t1=$(python3 -c 'import time;print(time.time())')
  docker stop -t 2 tr-lab-sampler > /dev/null; docker logs tr-lab-sampler 2> /dev/null | grep '^{' > "$OUTDIR/$n-$rep.samples"; docker rm -fv tr-lab-sampler > /dev/null
  cg=$(python3 -c "print(round(($u1-$u0)/1e6/($t1-$t0),4))")
  echo "$n,$rep,$out,$cg" >> "$OUTDIR/runs.csv"
  log "대기자 $n 회차$rep: $out, 컨테이너 전체 CPU=$cg"
  lab_rm tr-lab-bench
done; done
