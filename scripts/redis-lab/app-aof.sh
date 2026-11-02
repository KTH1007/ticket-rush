#!/bin/bash
# AOF 앱 테스트: 측정 스택(앱 + Postgres + Redis)에서 Redis를 SIGKILL로 죽였다 살렸을 때 대기열이 남는지
#  A. 새 설정(AOF everysec + save "")  B. 옛 설정 대조군(AOF 없음, 기본 RDB)
# 개발 스택(tr-redis, tr-postgres)은 건드리지 않는다. 끝나면 측정 스택을 지운다
# 환경 변수: REPO_DIR, USERS, OUT
cd "${REPO_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}" || exit 1
if [ -n "$(docker ps -aq --filter name=tr-measure-)" ]; then echo "거부: tr-measure- 컨테이너가 이미 있음" >&2; exit 1; fi
export PHONE_HMAC_KEY=${PHONE_HMAC_KEY:-$(openssl rand -base64 32)}
export PHONE_ENCRYPTION_KEY=${PHONE_ENCRYPTION_KEY:-$(openssl rand -base64 32)}
OUT=${OUT:-/tmp/aof-app}; mkdir -p "$OUT"; A=http://127.0.0.1:18081; USERS=${USERS:-300}
cat > "$OUT/old-redis.yml" <<'YML'
services:
  redis:
    command: [redis-server, --maxmemory, 512mb, --maxmemory-policy, noeviction, --appendonly, "no"]
YML
NEW="docker compose -f k6/docker-compose.measure.yml"
OLD="docker compose -f k6/docker-compose.measure.yml -f $OUT/old-redis.yml"
C=$NEW
log() { echo "[$(date +%H:%M:%S)] $*"; }
rcli() { $C exec -T redis redis-cli "$@" | tr -d '\r'; }
psql_() { $NEW exec -T postgres psql -U ticketrush -d ticketrush -tA "$@"; }
cleanup() { $NEW --profile two down -v --remove-orphans > /dev/null 2>&1; docker rmi tr-measure-app > /dev/null 2>&1; }
trap cleanup EXIT
wait_ready() { local i; for i in $(seq 1 120); do curl -s -m 2 "$A/actuator/health/readiness" | grep -q '"UP"' && return 0; sleep 2; done; return 1; }
wait_pong() { local i; for i in $(seq 1 100); do [ "$(rcli PING 2> /dev/null)" = PONG ] && return 0; sleep 0.2; done; return 1; }
status() { # 토큰 -> "HTTP코드 rank=N" (또는 코드와 에러 코드)
  local body code
  body=$(curl -s -m 10 -w '\n%{http_code}' "$A/api/events/$E/queues/me" -H "X-Queue-Token: $1")
  code=$(echo "$body" | tail -1)
  if [ "$code" = 200 ]; then echo "200 $(echo "$body" | head -1 | grep -o '"rank":[0-9]*')"; else echo "$code $(echo "$body" | head -1 | cut -c1-90)"; fi
}
register_n() { # 개수 -> tokens.txt
  : > "$OUT/tokens.txt"; local i
  for i in $(seq 1 "$1"); do
    curl -s -m 5 -D - -o /dev/null -X POST "$A/api/events/$E/queues" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-queue-token"{print $2}' >> "$OUT/tokens.txt"
  done
}
state() { echo "waiting=$(rcli ZCARD "queue:$E:waiting") seq=$(rcli GET "queue:$E:seq") aof_enabled=$(rcli INFO persistence | awk -F: '/^aof_enabled:/{print $2}') save=[$(rcli CONFIG GET save | tail -1)]"; }

scenario() { # 라벨
  local label=$1 T1 TM TL t0 t1 res
  log "=== 시나리오: $label"
  register_n "$USERS"
  T1=$(sed -n 1p "$OUT/tokens.txt"); TM=$(sed -n "$((USERS / 2))p" "$OUT/tokens.txt"); TL=$(sed -n "${USERS}p" "$OUT/tokens.txt")
  log "등록 $(wc -l < "$OUT/tokens.txt")명 | 상태: $(state)"
  log "crash 전 조회: 앞=[$(status "$T1")] 중간=[$(status "$TM")] 뒤=[$(status "$TL")]"
  sleep 1.5  # everysec fsync가 한 번 지나가게
  $C kill redis > /dev/null 2>&1
  sleep 1
  res=$(curl -s -m 10 -D "$OUT/h.txt" -o "$OUT/b.txt" -w '%{http_code}' "$A/api/events/$E/queues/me" -H "X-Queue-Token: $T1")
  log "Redis가 죽은 동안 조회 -> HTTP $res Retry-After=$(grep -i '^retry-after:' "$OUT/h.txt" | tr -d '\r' | awk '{print $2}') $(head -c 120 "$OUT/b.txt")"
  t0=$(date +%s)
  $C start redis > /dev/null 2>&1; wait_pong || { log "Redis가 돌아오지 않음"; return 1; }
  log "Redis 복귀 | 상태: $(state) | 기동 로그: $($C logs redis 2>&1 | grep -E 'DB loaded from|Ready to accept' | tail -2 | sed 's/^.*\* //' | paste -sd'/' -)"
  for i in $(seq 1 60); do res=$(status "$T1"); case "$res" in 200*|401*) break ;; esac; sleep 1; done
  t1=$(date +%s)
  log "crash 후 조회(복귀 후 ${t1}-${t0}초 이내 안정): 앞=[$res] 중간=[$(status "$TM")] 뒤=[$(status "$TL")]"
  log "crash 후 새 등록 1명: 토큰 $(curl -s -m 5 -D - -o /dev/null -X POST "$A/api/events/$E/queues" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-queue-token"{print "발급됨"}') | 이후 상태: $(state)"
}

log "== 스택 기동(승격 꺼짐)과 시드 (이미지 빌드 포함)"
export QUEUE_PROMOTION_INTERVAL=1h
$NEW up -d --build app >> "$OUT/compose.log" 2>&1 || { log "스택 기동 실패"; tail -20 "$OUT/compose.log"; exit 1; }
wait_ready || { log "앱이 뜨지 않음"; $NEW logs --tail 40 app 2>&1 | tail -40; exit 1; }
psql_ < scripts/seed-event.sql > /dev/null
E=$(psql_ -c "SELECT id FROM event ORDER BY id DESC LIMIT 1")
psql_ -c "UPDATE event SET opens_at = now() - interval '1 hour' WHERE id=$E" > /dev/null; rcli DEL "queue:$E:opensAt" > /dev/null
log "공연 $E, Redis 설정: $(rcli CONFIG GET appendonly | paste -sd' ' -) $(rcli CONFIG GET appendfsync | paste -sd' ' -) save=[$(rcli CONFIG GET save | tail -1)]"

C=$NEW; scenario "A. 새 설정: AOF everysec, 자동 RDB 끔"

log "== 옛 설정으로 Redis만 다시 만든다(AOF 없음, 기본 RDB)"
C=$OLD; $OLD up -d redis >> "$OUT/compose.log" 2>&1; wait_pong || { log "옛 설정 Redis가 뜨지 않음"; exit 1; }
rcli DEL "queue:$E:opensAt" > /dev/null
for i in $(seq 1 60); do wait_ready && break; sleep 1; done
scenario "B. 옛 설정 대조군: AOF 없음, 기본 RDB"
log "== 끝"
