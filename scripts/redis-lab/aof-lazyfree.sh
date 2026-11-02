#!/bin/bash
# lazyfree 설정별로 큰 ZSET이 지워질 때 서버가 얼마나 멈추는지: DEL, UNLINK, TTL 만료 (기본 100만 멤버)
# TTL 만료는 PEXPIRE로 1ms 뒤 만료시키고, 서버의 능동 만료 주기가 키를 지우는 동안 다른 연결의 PING 지연을 본다
# 환경 변수: N, REPS
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
N=${N:-1000000}; REPS=${REPS:-3}
trap lab_cleanup_all EXIT

run_case() { # 라벨 명령 키인자 redis옵션...
  local label=$1 cmd=$2 karg=$3 rep out; shift 3
  for ((rep = 1; rep <= REPS; rep++)); do
    aof_up tr-lab-aof "" --save "" --appendonly no --maxmemory 2gb --maxmemory-policy noeviction "$@" || return 1
    docker cp "$LAB_DIR/gen-zadd.awk" tr-lab-aof:/tmp/gen-zadd.awk
    docker exec tr-lab-aof sh -c "awk -v n=$N -v k=big -f /tmp/gen-zadd.awk | redis-cli --pipe" > /dev/null 2>&1
    out=$(lab_py "$LAB_DIR/probe.py" tr-lab-aof 6379 "$cmd" "$karg" 3 4 | tail -1)
    echo "$label | $cmd | 회차$rep | $(echo "$out" | python3 -c 'import sys,json; d=json.loads(sys.stdin.read()); print("명령 응답 %sms, PING 최대 %sms(명령 후 %.2fs), 10ms 초과 %s번, p99 %sms" % (d["cmd_ms"], d["max_ms"], d["max_at_s"]-d["cmd_at_s"], d["over10ms"], d["p99_ms"]))')"
    lab_rm tr-lab-aof
  done
}

log "대상: ZSET ${N}멤버 ($(docker run --rm "$LAB_REDIS_IMAGE" redis-server --version | grep -o 'v=[0-9.]*'))"
run_case "기본(lazyfree 모두 no)" DEL big
run_case "기본(lazyfree 모두 no)" PEXPIRE "big 1"
run_case "기본(lazyfree 모두 no)" UNLINK big
run_case "user-del yes" DEL big --lazyfree-lazy-user-del yes
run_case "user-del yes" PEXPIRE "big 1" --lazyfree-lazy-user-del yes
run_case "expire yes" PEXPIRE "big 1" --lazyfree-lazy-expire yes
