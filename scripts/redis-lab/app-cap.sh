#!/bin/bash
# 상한 앱 테스트: 측정 스택의 실제 앱에서 (1) 대기열 상한(50)에 닿으면 503 QUEUE_FULL과 Retry-After가 오고 순번과 IP 발급 수를 쓰지 않는지
# (2) 거절 카운터가 오르는지 (3) 정리된 공연에는 캐시한 시작 시각이 아직 먼 미래여도 등록이 닫히는지
# 개발 스택(tr-redis, tr-postgres)은 건드리지 않는다. 끝나면 측정 스택을 지운다
cd "${REPO_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}" || exit 1
if [ -n "$(docker ps -aq --filter name=tr-measure-)" ]; then echo "거부: tr-measure- 컨테이너가 이미 있음" >&2; exit 1; fi
export PHONE_HMAC_KEY=${PHONE_HMAC_KEY:-$(openssl rand -base64 32)}
export PHONE_ENCRYPTION_KEY=${PHONE_ENCRYPTION_KEY:-$(openssl rand -base64 32)}
export QUEUE_MAX_WAITING=50
OUT=${OUT:-/tmp/cap-app}; mkdir -p "$OUT"; A=http://127.0.0.1:18081; CAP=50; EXTRA=10
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
export QUEUE_PROMOTION_INTERVAL=1h
$C up -d --build app >> "$OUT/compose.log" 2>&1 || { log "스택 기동 실패"; tail -20 "$OUT/compose.log"; exit 1; }
wait_ready || { log "앱이 뜨지 않음"; exit 1; }
psql_ < scripts/seed-event.sql > /dev/null
E=$(psql_ -c "SELECT id FROM event ORDER BY id DESC LIMIT 1")
psql_ -c "UPDATE event SET opens_at = now() - interval '2 hours', starts_at = now() + interval '2 hours' WHERE id=$E" > /dev/null; rcli DEL "queue:$E:opensAt" "queue:$E:startsAt" > /dev/null
log "공연 $E, 상한 설정: $($C exec -T app printenv TICKET_RUSH_QUEUE_MAX_WAITING | tr -d '\r')"

log "== 1. 상한 ${CAP}에 닿을 때까지 $((CAP + EXTRA))명을 순서대로 등록"
: > "$OUT/codes.txt"
for i in $(seq 1 $((CAP + EXTRA))); do
  curl -s -m 5 -D "$OUT/h$i.txt" -o "$OUT/b$i.txt" -w '%{http_code}\n' -X POST "$A/api/events/$E/queues" >> "$OUT/codes.txt"
done
log "응답 코드 분포: $(sort "$OUT/codes.txt" | uniq -c | awk '{printf "%s가 %s번 ", $2, $1}')"
FIRST_FULL=$(grep -n '^503$' "$OUT/codes.txt" | head -1 | cut -d: -f1)
log "첫 503은 ${FIRST_FULL}번째 요청 | Retry-After=$(grep -i '^retry-after:' "$OUT/h${FIRST_FULL}.txt" | tr -d '\r' | awk '{print $2}') | 본문=$(head -c 220 "$OUT/b${FIRST_FULL}.txt")"
IPKEY=$(rcli --scan --pattern "queue:$E:register-ip:*" | head -1)
log "Redis: waiting=$(rcli ZCARD "queue:$E:waiting") seq=$(rcli GET "queue:$E:seq") IP발급수=$(rcli GET "$IPKEY") token키=$(rcli --scan --pattern "queue:$E:token:*" | wc -l | tr -d ' ')   <- 모두 ${CAP}이어야 함"
log "거절 카운터: $(curl -s -m 5 "$A/actuator/prometheus" | grep '^queue_register_rejected' | tr '\n' ' ')"

log "== 2. 정리 뒤 등록 (캐시한 시작 시각은 아직 먼 미래)"
psql_ -c "UPDATE event SET starts_at = now() - interval '10 seconds' WHERE id=$E" > /dev/null
for i in $(seq 1 30); do [ "$(rcli EXISTS "queue:$E:cleaned")" = 1 ] && break; sleep 1; done
log "정리됨(${i}초): waiting 존재=$(rcli EXISTS "queue:$E:waiting") seq 존재=$(rcli EXISTS "queue:$E:seq") 정리 표시=$(rcli EXISTS "queue:$E:cleaned")"
RES=$(curl -s -m 5 -o "$OUT/closed.txt" -w '%{http_code}' -X POST "$A/api/events/$E/queues")
log "정리된 공연에 새 등록 -> HTTP $RES $(head -c 200 "$OUT/closed.txt")   <- 이전에는 201이었음"
log "그 뒤 Redis: waiting 존재=$(rcli EXISTS "queue:$E:waiting") seq 존재=$(rcli EXISTS "queue:$E:seq")   <- 둘 다 0이어야 함"
log "== 끝"
