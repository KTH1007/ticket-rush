#!/bin/bash
# 정리 앱 테스트: 측정 스택의 실제 앱에서 (1) 공연이 시작된 뒤 대기열이 정리되고 대기자가 401이 되는지 (2) 대기열이 비면 순번 키에 TTL이 걸리고 등록이 없애는지
# 개발 스택(tr-redis, tr-postgres)은 건드리지 않는다. 끝나면 측정 스택을 지운다
cd "${REPO_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}" || exit 1
if [ -n "$(docker ps -aq --filter name=tr-measure-)" ]; then echo "거부: tr-measure- 컨테이너가 이미 있음" >&2; exit 1; fi
export PHONE_HMAC_KEY=${PHONE_HMAC_KEY:-$(openssl rand -base64 32)}
export PHONE_ENCRYPTION_KEY=${PHONE_ENCRYPTION_KEY:-$(openssl rand -base64 32)}
OUT=${OUT:-/tmp/cleanup-app}; mkdir -p "$OUT"; A=http://127.0.0.1:18081; USERS=${USERS:-200}
cat > "$OUT/override.yml" <<'YML'
services:
  app:
    environment:
      TICKET_RUSH_QUEUE_CLEANUP_INTERVAL: 3s
      TICKET_RUSH_QUEUE_CLEANUP_GRACE_AFTER_START: 0s
YML
C="docker compose -f k6/docker-compose.measure.yml -f $OUT/override.yml"
log() { echo "[$(date +%H:%M:%S)] $*"; }
rcli() { $C exec -T redis redis-cli "$@" | tr -d '\r'; }
psql_() { $C exec -T postgres psql -U ticketrush -d ticketrush -tA "$@"; }
cleanup() { $C --profile two down -v --remove-orphans > /dev/null 2>&1; docker rmi tr-measure-app > /dev/null 2>&1; }
trap cleanup EXIT
wait_ready() { local i; for i in $(seq 1 120); do curl -s -m 2 "$A/actuator/health/readiness" | grep -q '"UP"' && return 0; sleep 2; done; return 1; }
up() { export QUEUE_PROMOTION_INTERVAL=$1; $C up -d --build app >> "$OUT/compose.log" 2>&1 || return 1; wait_ready; }
status() { # 이벤트 토큰 -> "HTTP코드 rank=N 또는 에러 앞부분"
  local body code
  body=$(curl -s -m 10 -w '\n%{http_code}' "$A/api/events/$1/queues/me" -H "X-Queue-Token: $2")
  code=$(echo "$body" | tail -1)
  if [ "$code" = 200 ]; then echo "200 $(echo "$body" | head -1 | grep -o '"rank":[0-9]*')"; else echo "$code $(echo "$body" | head -1 | grep -o '"code":"[A-Z_]*"\|"title":"[^"]*"' | head -1)"; fi
}
register_n() { # 이벤트 개수 -> tokens.txt
  : > "$OUT/tokens.txt"; local i
  for i in $(seq 1 "$2"); do
    curl -s -m 5 -D - -o /dev/null -X POST "$A/api/events/$1/queues" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-queue-token"{print $2}' >> "$OUT/tokens.txt"
  done
}
keys() { echo "waiting=$(rcli ZCARD "queue:$1:waiting") seq=$(rcli GET "queue:$1:seq") lastPromotedSeq=$(rcli GET "queue:$1:lastPromotedSeq") cleaned=$(rcli EXISTS "queue:$1:cleaned") 활성집합에있음=$(rcli SISMEMBER queue:activeEventIds "$1") token키=$(rcli --scan --pattern "queue:$1:token:*" | wc -l | tr -d ' ')"; }
ttl() { echo "TTL seq=$(rcli TTL "queue:$1:seq") lastPromotedSeq=$(rcli TTL "queue:$1:lastPromotedSeq")"; }

log "== 스택 기동(승격 꺼짐, 정리 주기 3초, 유예 0)과 시드"
up 1h || { log "앱이 뜨지 않음"; tail -20 "$OUT/compose.log"; exit 1; }
log "정리 설정: $($C logs app 2>&1 | grep -c 'Started') (앱 기동 확인)"
psql_ < scripts/seed-event.sql > /dev/null
E=$(psql_ -c "SELECT id FROM event ORDER BY id DESC LIMIT 1")
psql_ -c "UPDATE event SET opens_at = now() - interval '2 hours', starts_at = now() + interval '2 hours' WHERE id=$E" > /dev/null; rcli DEL "queue:$E:opensAt" "queue:$E:startsAt" > /dev/null

log "== 1. 정리 (공연 $E)"
register_n "$E" "$USERS"; T1=$(sed -n 1p "$OUT/tokens.txt"); TM=$(sed -n "$((USERS / 2))p" "$OUT/tokens.txt"); TL=$(sed -n "${USERS}p" "$OUT/tokens.txt")
log "등록 $(wc -l < "$OUT/tokens.txt")명 | $(keys "$E")"
log "정리 전 조회: 앞=[$(status "$E" "$T1")] 중간=[$(status "$E" "$TM")] 뒤=[$(status "$E" "$TL")]"
sleep 8
log "공연 시작 전 8초 뒤(정리 주기 3초, 2번 이상 돌았어야 함): $(keys "$E") <- 그대로여야 함"
USED_BEFORE=$(rcli INFO memory | awk -F: '/^used_memory:/{print $2}')
psql_ -c "UPDATE event SET starts_at = now() - interval '10 seconds' WHERE id=$E" > /dev/null
for i in $(seq 1 30); do [ "$(rcli EXISTS "queue:$E:cleaned")" = 1 ] && break; sleep 1; done
log "공연 시작(DB 갱신) 후 ${i}초 이내 정리됨: $(keys "$E")"
log "정리 후 조회: 앞=[$(status "$E" "$T1")] 중간=[$(status "$E" "$TM")] 뒤=[$(status "$E" "$TL")]"
log "정리 표시 TTL=$(rcli TTL "queue:$E:cleaned")초 | 정리 전 used_memory=$USED_BEFORE -> 정리 후 $(rcli INFO memory | awk -F: '/^used_memory:/{print $2}')"
log "시작한 공연에 새 등록: $(curl -s -m 5 -o /dev/null -w 'HTTP %{http_code}' -X POST "$A/api/events/$E/queues")"
log "정리 로그: $($C logs app 2>&1 | grep '대기열 키를 정리했습니다' | tail -1 | sed 's/^.*공연/공연/')"

log "== 2. 대기열이 비면 순번 키에 TTL (새 공연)"
psql_ < scripts/seed-event.sql > /dev/null
E2=$(psql_ -c "SELECT id FROM event ORDER BY id DESC LIMIT 1")
psql_ -c "UPDATE event SET opens_at = now() - interval '2 hours', starts_at = now() + interval '2 hours' WHERE id=$E2" > /dev/null; rcli DEL "queue:$E2:opensAt" "queue:$E2:startsAt" > /dev/null
register_n "$E2" 20
log "등록 20명 | $(keys "$E2") | $(ttl "$E2")  <- 줄에 사람이 있으면 TTL -1이어야 함"
up 1s || { log "앱 재기동 실패"; exit 1; }
for i in $(seq 1 30); do [ "$(rcli ZCARD "queue:$E2:waiting")" = 0 ] && break; sleep 1; done
sleep 1
log "승격으로 비운 뒤(${i}초): $(keys "$E2") | $(ttl "$E2")  <- 활성집합에서 빠지고 TTL이 걸려야 함"
up 1h || { log "앱 재기동 실패"; exit 1; }
log "승격을 끈 뒤 비운 상태 확인: $(keys "$E2") | $(ttl "$E2")"
NEW=$(curl -s -m 5 -D - -o /dev/null -X POST "$A/api/events/$E2/queues" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-queue-token"{print $2}')
log "비운 뒤 새 등록: $(keys "$E2") | $(ttl "$E2")  <- TTL -1로 돌아가고 순번이 이어져야 함 | 새 사용자 조회=[$(status "$E2" "$NEW")]"
log "== 끝"
