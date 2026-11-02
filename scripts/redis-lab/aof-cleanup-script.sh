#!/bin/bash
# 정리 스크립트 실험: 50만 대기자 데이터에서 실제 queue-cleanup.lua(UNLINK)와, 같은 스크립트의 UNLINK만 DEL로 바꾼 변형을 CPU 1코어에서 비교한다
#  - 스크립트가 도는 동안 다른 연결의 PING 지연(스크립트는 끊김 없이 실행되므로 그동안 다른 명령이 기다린다)
#  - 50만 멤버 ZSET의 MEMORY USAGE
# 변형: unlink(실제 스크립트), del(UNLINK를 DEL로, lazyfree 기본값), del-lazy(DEL, lazyfree-lazy-user-del yes)
# 환경 변수: N, REPS, SCRIPT(정리 스크립트 경로)
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
N=${N:-500000}; REPS=${REPS:-3}
SCRIPT=${SCRIPT:-$(cd "$LAB_DIR/../.." && pwd)/src/main/resources/scripts/queue-cleanup.lua}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"; lab_cleanup_all' EXIT
cp "$SCRIPT" "$TMP/unlink.lua"; sed 's/UNLINK/DEL/' "$SCRIPT" > "$TMP/del.lua"
grep -c "redis.call('UNLINK'" "$TMP/unlink.lua" | sed 's/^/unlink 변형의 UNLINK 호출 수: /'; grep -c "redis.call('DEL'" "$TMP/del.lua" | sed 's/^/del 변형의 DEL 호출 수: /'

load_data() {
  docker cp "$LAB_DIR/gen-queue.awk" tr-lab-aof:/tmp/gen-queue.awk
  docker exec tr-lab-aof sh -c "awk -v n=$N -f /tmp/gen-queue.awk | redis-cli --pipe" > /dev/null 2>&1
  rcli tr-lab-aof SET queue:1:seq "$N" > /dev/null; rcli tr-lab-aof SET queue:1:lastPromotedSeq 0 > /dev/null; rcli tr-lab-aof SADD queue:activeEventIds 1 > /dev/null
}

run_variant() { # 라벨 lua파일 redis옵션...
  local label=$1 lua=$2 rep out mem; shift 2
  for ((rep = 1; rep <= REPS; rep++)); do
    aof_up tr-lab-aof "--cpus 1" --save "" --appendonly no --maxmemory 2gb --maxmemory-policy noeviction "$@" || return 1
    load_data
    if [ "$label" = unlink ] && [ "$rep" = 1 ]; then
      mem=$(rcli tr-lab-aof MEMORY USAGE queue:1:waiting SAMPLES 0)
      echo "  [데이터] ZSET ${N}멤버 MEMORY USAGE=${mem}바이트($((mem / 1048576))MiB), 멤버당 약 $((mem / N))바이트, 인코딩=$(rcli tr-lab-aof OBJECT ENCODING queue:1:waiting), 전체 used_memory=$(info_field tr-lab-aof memory used_memory_human), 키 수=$(rcli tr-lab-aof DBSIZE)"
    fi
    out=$(docker run --rm -i --network $LAB_NET -e LUA="$(cat "$lua")" python:3-alpine python -u - tr-lab-aof 3 3 < "$LAB_DIR/script_probe.py" | tail -1)
    echo "  [$label] 회차$rep: $out"
    lab_rm tr-lab-aof
  done
}

log "대상: 대기열 ${N}명(ZSET ${N} + 토큰 키 ${N}), CPU 1코어, 기본 lazyfree(no)에서 비교"
run_variant unlink "$TMP/unlink.lua"
run_variant del "$TMP/del.lua"
run_variant del-lazy "$TMP/del.lua" --lazyfree-lazy-user-del yes
