#!/bin/bash
# 실험 3: 큰 ZSET을 DEL과 UNLINK로 지울 때 다른 연결이 보는 지연
# 사용: N=1000000 REPS=5 ./exp3-bigkey.sh   (N: ZSET 멤버 수, REPS: 기본 설정에서 명령마다 반복 횟수)
source "$(dirname "$0")/common.sh"
N=${N:-1000000}; REPS=${REPS:-5}; EXTRA_REPS=${EXTRA_REPS:-3}
NAME=tr-lab-bigkey
RESULT=${RESULT:-/tmp/exp3-result.jsonl}; : > "$RESULT"
trap lab_cleanup_all EXIT

lab_redis $NAME --save "" --appendonly no --maxmemory 2gb --maxmemory-policy noeviction
docker cp "$LAB_DIR/gen-zadd.awk" $NAME:/tmp/gen-zadd.awk
log "Redis $(rcli $NAME INFO server | grep -m1 redis_version | tr -d '\r')"
log "기본 lazyfree 설정: $(rcli $NAME CONFIG GET 'lazyfree-*' | paste -sd' ' -)"

fill() {
  local t0=$SECONDS out
  out=$(docker exec $NAME sh -c "awk -v n=$N -v k=big -f /tmp/gen-zadd.awk | redis-cli --pipe" 2>&1 | grep -E "errors:|replies:" | paste -sd' ' -)
  log "적재 ${N}개 $((SECONDS - t0))초 ($out) ZCARD=$(rcli $NAME ZCARD big) used_memory=$(rcli $NAME INFO memory | grep -m1 '^used_memory_human' | tr -d '\r')"
}
wait_idle() { # 지연 해제가 끝날 때까지 기다린다
  local i
  for i in $(seq 1 120); do
    [ "$(rcli $NAME INFO memory | grep '^lazyfree_pending_objects' | cut -d: -f2 | tr -d '\r')" = 0 ] && break
    sleep 0.5
  done
  sleep 1
}
run_one() { # 설정이름 명령
  local label=$1 cmd=$2 out
  fill
  out=$(lab_py "$LAB_DIR/probe.py" $NAME 6379 "$cmd" big 2 2)
  echo "{\"config\":\"$label\",\"result\":$out}" >> "$RESULT"
  log "$label $cmd -> $out"
  wait_idle
}

for ((i = 1; i <= REPS; i++)); do run_one default DEL; done
for ((i = 1; i <= REPS; i++)); do run_one default UNLINK; done
rcli $NAME CONFIG SET lazyfree-lazy-user-del no > /dev/null
for ((i = 1; i <= EXTRA_REPS; i++)); do run_one "lazy-user-del=no" DEL; done
rcli $NAME CONFIG SET lazyfree-lazy-user-del yes > /dev/null
for ((i = 1; i <= EXTRA_REPS; i++)); do run_one "lazy-user-del=yes" DEL; done

python3 - "$RESULT" <<'PY'
import json, sys, statistics as st
rows = [json.loads(l) for l in open(sys.argv[1])]
groups = {}
for r in rows:
    groups.setdefault((r["config"], r["result"]["cmd"]), []).append(r["result"])
print("\n| 설정 | 명령 | 횟수 | 명령 응답 시간(ms) | 다른 연결 최대 지연(ms) | 10ms 넘은 PING 수 |")
print("|---|---|---|---|---|---|")
for (cfg, cmd), v in groups.items():
    f = lambda k: ", ".join(str(x[k]) for x in v)
    print(f"| {cfg} | {cmd} | {len(v)} | {f('cmd_ms')} | {f('max_ms')} | {f('over10ms')} |")
PY
