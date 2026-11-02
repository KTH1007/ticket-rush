#!/bin/bash
# AOF 실험 3: 대기열 규모 데이터의 재시작 로드 시간, rewrite와 BGSAVE 중 지연과 메모리, 한도 근처에서의 fork 위험
#  A. N명 대기열(ZSET N + 토큰 키 N)의 재시작 로드 시간: AOF(rewrite 전, rewrite 뒤)와 RDB
#  B. 같은 데이터에서 쓰기 부하 중 BGREWRITEAOF와 BGSAVE가 PING 지연과 컨테이너 메모리에 주는 영향 (CPU 2코어, 1코어)
#  C. maxmemory 한도 근처 데이터(BIGN 키)에서 같은 비교 (컨테이너 메모리 한도 1GiB, compose와 같음)
# 환경 변수: N, BIGN, STAGES(A B C), OUTDIR
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
N=${N:-500000}; BIGN=${BIGN:-2800000}; STAGES=${STAGES:-"A B C"}
OUTDIR=${OUTDIR:-/tmp/redis-lab-aof-load-rewrite}; mkdir -p "$OUTDIR"
trap 'kill $SAMPLER 2> /dev/null; lab_cleanup_all' EXIT

load_queue() { # 컨테이너
  docker cp "$LAB_DIR/gen-queue.awk" "$1:/tmp/gen-queue.awk"
  docker exec "$1" sh -c "awk -v n=$N -f /tmp/gen-queue.awk | redis-cli --pipe" 2>&1 | grep -E "errors:|replies:" | paste -sd' ' -
}
sizes() { docker exec "$1" sh -c 'ls -l /data /data/appendonlydir 2>/dev/null | grep -E "dump.rdb|appendonly.aof" | awk "{printf \"%s=%.1fMiB \", \$NF, \$5/1048576}"'; echo; }
wait_idle() { wait_for 600 "[ \"\$(info_field $1 persistence aof_rewrite_in_progress)\" = 0 ] && [ \"\$(info_field $1 persistence rdb_bgsave_in_progress)\" = 0 ]"; }
restarts() { # 컨테이너 설명 횟수
  local n=$1 label=$2 k
  for ((k = 1; k <= ${3:-3}; k++)); do aof_crash_restart "$n"; echo "  [$label] 재시작 $k: $(load_line "$n")"; done
}

stage_a() {
  log "A. 로드 시간 (대기열 ${N}명: ZSET ${N} + 토큰 키 ${N})"
  set_mode aof-everysec; aof_up tr-lab-aof "--cpus 1 --memory 1g" "${ARGS[@]}"
  log "적재: $(load_queue tr-lab-aof) | DBSIZE=$(rcli tr-lab-aof DBSIZE) used_memory=$(info_field tr-lab-aof memory used_memory_human)"
  wait_idle tr-lab-aof; echo "  파일(rewrite 전): $(sizes tr-lab-aof)"
  restarts tr-lab-aof "AOF rewrite 전(명령 재생)"
  rcli tr-lab-aof BGREWRITEAOF > /dev/null; sleep 0.5; wait_idle tr-lab-aof; echo "  파일(rewrite 뒤): $(sizes tr-lab-aof)"
  restarts tr-lab-aof "AOF rewrite 뒤(RDB base)"
  lab_rm tr-lab-aof
  set_mode off; aof_up tr-lab-aof "--cpus 1 --memory 1g" "${ARGS[@]}"
  load_queue tr-lab-aof > /dev/null; rcli tr-lab-aof SAVE > /dev/null; echo "  파일(RDB): $(sizes tr-lab-aof)"
  restarts tr-lab-aof "RDB"
  lab_rm tr-lab-aof
}

# 컨테이너 모드 CPU 트리거 키모드 키개수 라벨 [데이터 준비 함수]
probe_run() {
  local mode=$1 cpus=$2 trigger=$3 keymode=$4 nkeys=$5 label=$6 prep=$7 res peak base oom
  set_mode "$mode"
  aof_up tr-lab-aof "--cpus $cpus --memory 1g" "${ARGS[@]}" --enable-debug-command yes
  $prep
  wait_idle tr-lab-aof
  : > "$OUTDIR/mem.txt"
  ( while :; do docker exec tr-lab-aof cat /sys/fs/cgroup/memory.current 2> /dev/null; sleep 0.1; done >> "$OUTDIR/mem.txt" ) &
  SAMPLER=$!
  lab_rm tr-lab-client
  res=$(docker run --rm -i --name tr-lab-client --network $LAB_NET python:3-alpine python -u - tr-lab-aof "$trigger" 6 6 4 10000 "$keymode" "$nkeys" < "$LAB_DIR/rewrite_probe.py" 2>&1 | tail -1)
  kill $SAMPLER 2> /dev/null; wait $SAMPLER 2> /dev/null
  base=$(grep -E '^[0-9]+$' "$OUTDIR/mem.txt" | head -1); peak=$(grep -E '^[0-9]+$' "$OUTDIR/mem.txt" | sort -n | tail -1)
  oom=$(docker inspect -f '{{.State.OOMKilled}}/{{.State.Status}}' tr-lab-aof 2> /dev/null)
  echo "  [$label] 컨테이너 메모리: 시작 $(( ${base:-0} / 1048576 ))MiB -> 최대 $(( ${peak:-0} / 1048576 ))MiB (한도 1024MiB), OOMKilled/상태=$oom"
  echo "  [$label] $res"
  echo "{\"label\":\"$label\",\"cpus\":$cpus,\"mem_start_mib\":$(( ${base:-0} / 1048576 )),\"mem_peak_mib\":$(( ${peak:-0} / 1048576 )),\"oom\":\"$oom\",\"probe\":${res:-null}}" >> "$OUTDIR/summary.jsonl" 2> /dev/null
  lab_rm tr-lab-aof tr-lab-client
}
prep_queue() { log "적재: $(load_queue tr-lab-aof)"; }
prep_big() { rcli tr-lab-aof DEBUG POPULATE "$BIGN" key 100 > /dev/null; log "DEBUG POPULATE ${BIGN}: used_memory=$(info_field tr-lab-aof memory used_memory_human) (maxmemory 512MB)"; }

stage_b() {
  log "B. 쓰기 부하(스레드 4개, 목표 초당 약 4만, 토큰 키 덮어쓰기) 중 rewrite와 BGSAVE의 영향 (대기열 ${N}명)"
  local cpus
  for cpus in 2 1; do
    probe_run aof-everysec $cpus BGREWRITEAOF token "$N" "B AOF rewrite, CPU ${cpus}" prep_queue
    probe_run off $cpus BGSAVE token "$N" "B RDB BGSAVE, CPU ${cpus}" prep_queue
  done
}
stage_c() {
  log "C. 한도 근처 데이터(${BIGN}키, 100바이트 값)에서의 fork 위험 (CPU 2코어, 컨테이너 1GiB)"
  probe_run aof-everysec 2 BGREWRITEAOF big "$BIGN" "C AOF rewrite 한도 근처" prep_big
  probe_run off 2 BGSAVE big "$BIGN" "C RDB BGSAVE 한도 근처" prep_big
}

: > "$OUTDIR/summary.jsonl"
for s in $STAGES; do case $s in A) stage_a ;; B) stage_b ;; C) stage_c ;; esac; done
log "요약: $OUTDIR/summary.jsonl"
