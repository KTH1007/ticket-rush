#!/bin/bash
# 토큰 키 대량 동시 만료: 같은 순간에 만료되는 키가 많을 때 서버가 얼마나 멈추는지, 만료 시각을 퍼뜨리면 어떻게 달라지는지
# aligned는 모든 키가 같은 밀리초에, spread는 SPREAD_SEC초에 고르게 만료된다. 앱의 Redis 옵션 그대로 띄우고 sampler.py가 PING 지연과 메인 스레드 CPU를 읽는다
# 환경 변수: N, REPS, MODES, SPREAD_SEC, LEAD_SEC(적재가 끝나고 첫 만료까지 여유), CPUS, EXTRA(redis-server 추가 인자), OUTDIR
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
N=${N:-500000}; REPS=${REPS:-3}; MODES=${MODES:-"aligned spread"}; SPREAD_SEC=${SPREAD_SEC:-60}; LEAD_SEC=${LEAD_SEC:-40}
OUTDIR=${OUTDIR:-/tmp/redis-lab-expire}; mkdir -p "$OUTDIR"
trap 'docker rm -fv tr-lab-sampler > /dev/null 2>&1; lab_cleanup_all' EXIT

host_idle() { top -l 2 -n 0 -s 1 2> /dev/null | grep "^CPU usage" | tail -1 | sed 's/.* \([0-9.]*\)% idle.*/\1/'; }
stat_line() { rcli tr-lab-exp INFO stats | grep -E '^(expired_keys|expired_stale_perc|expired_time_cap_reached_count|expire_cycle_cpu_milliseconds):' | tr -d '\r' | tr '\n' ' '; }
server_ms() { local s us; read -r s us <<< "$(rcli tr-lab-exp TIME | tr '\n' ' ')"; echo $(( s * 1000 + 10#$us / 1000 )); }

run_one() { # 모드 회차
  local mode=$1 f="$OUTDIR/$1-$2" base_ms now s us size deadline
  set_mode aof-everysec
  ARGS+=(--lazyfree-lazy-user-del yes --lazyfree-lazy-expire yes)
  # shellcheck disable=SC2206
  [ -n "${EXTRA:-}" ] && ARGS+=($EXTRA)
  aof_up tr-lab-exp "--cpus ${CPUS:-2}" "${ARGS[@]}" || return 1
  docker cp "$LAB_DIR/gen-ttl.awk" tr-lab-exp:/tmp/gen-ttl.awk
  base_ms=$(( $(server_ms) + LEAD_SEC * 1000 ))
  docker exec tr-lab-exp sh -c "awk -v n=$N -v base=$base_ms -v mode=$mode -v spread_ms=$((SPREAD_SEC * 1000)) -f /tmp/gen-ttl.awk | redis-cli --pipe" > "$f.load" 2>&1
  now=$(server_ms)
  if [ "$(rcli tr-lab-exp DBSIZE)" != "$N" ] || [ "$now" -ge "$base_ms" ]; then
    echo "적재 실패 또는 적재 중 만료 시작(여유 $(( base_ms - now ))ms, 키 $(rcli tr-lab-exp DBSIZE))" >&2; return 1
  fi
  printf '{"base_ms":%s,"n":%s,"load_margin_ms":%s,"hz":%s}\n' "$base_ms" "$N" "$(( base_ms - now ))" "$(rcli tr-lab-exp CONFIG GET hz | tail -1)" > "$f.meta"
  docker rm -fv tr-lab-sampler > /dev/null 2>&1
  docker run -d --name tr-lab-sampler --network $LAB_NET -v "$LAB_DIR/sampler.py:/s.py:ro" python:3-alpine python -u /s.py tr-lab-exp > /dev/null
  sleep 3; host_idle > "$f.idle"
  stat_line > "$f.stats-before"; info_field tr-lab-exp persistence aof_current_size > "$f.aof-before"; info_field tr-lab-exp memory used_memory > "$f.mem-before"; info_field tr-lab-exp persistence aof_rewrites > "$f.rw-before"
  : > "$f.dbsize"; deadline=$(( base_ms + (SPREAD_SEC + 180) * 1000 ))
  while :; do
    read -r s us size <<< "$(docker exec tr-lab-exp sh -c 'redis-cli TIME | tr "\n" " "; redis-cli DBSIZE')"
    now=$(( s * 1000 + 10#$us / 1000 ))
    echo "$(( now - base_ms )) $size" >> "$f.dbsize"
    [ "$size" = 0 ] && break
    [ "$now" -gt "$deadline" ] && break
    sleep 1
  done
  sleep 3
  stat_line > "$f.stats-after"; info_field tr-lab-exp persistence aof_current_size > "$f.aof-after"; info_field tr-lab-exp memory used_memory > "$f.mem-after"; info_field tr-lab-exp persistence aof_rewrites > "$f.rw-after"
  docker stop -t 2 tr-lab-sampler > /dev/null; docker logs tr-lab-sampler 2> /dev/null | grep '^{' > "$f.samples"; docker rm -fv tr-lab-sampler > /dev/null
  lab_rm tr-lab-exp
}

for mode in $MODES; do for ((r = 1; r <= REPS; r++)); do
  log "$mode 회차$r (키 $N, 만료 퍼짐 ${SPREAD_SEC}s)"; run_one "$mode" "$r" || log "실패: $mode $r"
done; done
log "끝: $OUTDIR (요약: python3 $LAB_DIR/expire-summarize.py $OUTDIR)"
