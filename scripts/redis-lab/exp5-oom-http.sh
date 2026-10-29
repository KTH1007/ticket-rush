#!/bin/bash
# OOM 앱 전체 실험: 측정 스택(tr-measure-*)의 앱을 Redis 메모리 한도에 닿게 하고 실제 HTTP 요청으로 동작을 본다
# 사용: scripts/redis-lab/exp5-oom-http.sh   (REPO_DIR로 다른 작업 트리를 지정할 수 있다. 앱 이미지를 그 트리에서 빌드한다)
cd "${REPO_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}" || exit 1
# 끝날 때 측정 스택을 지우므로 이미 떠 있는 측정 스택(다른 측정 중일 수 있음)이 있으면 거부한다
if [ -n "$(docker ps -aq --filter name=tr-measure-)" ]; then echo "거부: tr-measure- 컨테이너가 이미 있음. 진행 중인 측정이 끝난 뒤 다시 실행" >&2; exit 1; fi
COMPOSE="docker compose -f k6/docker-compose.measure.yml"
# 일회용 테스트 키(측정 하네스와 같은 방식). 값은 출력하지 않는다
export PHONE_HMAC_KEY=${PHONE_HMAC_KEY:-$(openssl rand -base64 32)}
export PHONE_ENCRYPTION_KEY=${PHONE_ENCRYPTION_KEY:-$(openssl rand -base64 32)}
OUT=${OUT:-/tmp/oom-http}; mkdir -p "$OUT"; A=http://127.0.0.1:18081
USERS=${USERS:-400}
log() { echo "[$(date +%H:%M:%S)] $*"; }
rcli() { $COMPOSE exec -T redis redis-cli "$@" | tr -d '\r'; }
psql_() { $COMPOSE exec -T postgres psql -U ticketrush -d ticketrush -tA "$@"; }
cleanup() { $COMPOSE --profile two down -v --remove-orphans > /dev/null 2>&1; docker rmi tr-measure-app > /dev/null 2>&1; }
trap cleanup EXIT
wait_ready() { local i; for i in $(seq 1 120); do curl -s -m 2 "$A/actuator/health/readiness" | grep -q '"UP"' && return 0; sleep 2; done; return 1; }
up() { export QUEUE_PROMOTION_INTERVAL=$1; $COMPOSE up -d --build app >> "$OUT/compose.log" 2>&1; wait_ready || { log "앱이 뜨지 않음"; $COMPOSE ps -a > "$OUT/ps-on-fail.txt" 2>&1; $COMPOSE logs --no-color app > "$OUT/app-start-fail.log" 2>&1; exit 1; }; }
probe() { # 라벨, curl 인자들
  local label=$1 res; shift
  res=$(curl -s -m 10 -D "$OUT/headers.txt" -o "$OUT/body.txt" -w '%{http_code} %{time_total}s' "$@")
  log "$label -> HTTP $res Retry-After=$(grep -i '^retry-after:' "$OUT/headers.txt" | tr -d '\r' | awk '{print $2}' | head -1) | $(head -c 200 "$OUT/body.txt" | tr '\n' ' ')"
}
used() { rcli INFO memory | awk -F: '/^used_memory:/{print $2}'; }
make_oom() { # 한도를 사용량보다 낮춰 확실히 넘긴 상태로 만들고, 거부가 두 번 연속 확인될 때까지 본다
  local u; u=$(used); rcli CONFIG SET maxmemory $((u - 512 * 1024)) > /dev/null
  sleep 1
  log "OOM 확인1: $(rcli SET probe x)"; sleep 1; log "OOM 확인2: $(rcli SET probe x) | used_memory=$(used) maxmemory=$(rcli CONFIG GET maxmemory | tail -1)"
}
active_count() { rcli --scan --pattern "queue:$E:active:*" | wc -l | tr -d ' '; }

log "== 1. 스택 기동(승격 꺼짐)과 시드"
up 1h
psql_ < scripts/seed-event.sql > /dev/null
E=$(psql_ -c "SELECT id FROM event ORDER BY id DESC LIMIT 1")
psql_ -c "UPDATE event SET opens_at = now() - interval '1 hour' WHERE id=$E" > /dev/null; rcli DEL "queue:$E:opensAt" > /dev/null
SEATS=($(psql_ -c "SELECT id FROM seat WHERE event_id=$E ORDER BY ordinal LIMIT 40"))
log "공연 $E, 좌석 ${#SEATS[@]}개 확보, Redis used_memory=$(used)"
: > "$OUT/tokens.txt"
for i in $(seq 1 "$USERS"); do
  curl -s -m 5 -D - -o /dev/null -X POST "$A/api/events/$E/queues" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-queue-token"{print $2}' >> "$OUT/tokens.txt"
done
log "등록 완료: 토큰 $(wc -l < "$OUT/tokens.txt")개, waiting=$(rcli ZCARD "queue:$E:waiting"), used_memory=$(used)"
T_FIRST=$(sed -n 1p "$OUT/tokens.txt"); T_MID=$(sed -n 200p "$OUT/tokens.txt"); T_LAST=$(sed -n "${USERS}p" "$OUT/tokens.txt")

log "== 2. 메모리를 가득 채운다"
U=$(used); rcli CONFIG SET maxmemory $((U + 3 * 1024 * 1024)) > /dev/null
awk 'BEGIN{v=sprintf("%1024s","");gsub(/ /,"x",v);for(i=1;i<=6000;i++)printf "SET filler:%d %s\r\n",i,v}' | $COMPOSE exec -T redis redis-cli --pipe 2>&1 | grep -E "errors:|replies:" | paste -sd' ' - | sed 's/^/채움 결과: /'
make_oom
echo "$(date +%s)" > "$OUT/oom_start"

log "== 3. OOM 상태(승격 꺼짐)의 HTTP 동작"
probe "새 줄서기 등록" -X POST "$A/api/events/$E/queues"
probe "대기자 상태 조회(앞)" "$A/api/events/$E/queues/me" -H "X-Queue-Token: $T_FIRST"
probe "대기자 상태 조회(뒤)" "$A/api/events/$E/queues/me" -H "X-Queue-Token: $T_LAST"
probe "승격 전 대기자의 좌석 홀드" -X POST "$A/api/events/$E/seats/hold" -H "Content-Type: application/json" -H "X-Queue-Token: $T_FIRST" -d "{\"seatIds\":[${SEATS[0]}],\"phoneNumber\":\"01000000001\"}"
probe "헬스" "$A/actuator/health"
log "waiting=$(rcli ZCARD "queue:$E:waiting"), 활성=$(active_count)"

log "== 4. OOM인 채로 승격을 켠다(앱 재기동)"
up 1s
sleep 8
log "승격 후: waiting=$(rcli ZCARD "queue:$E:waiting"), 활성=$(active_count), used_memory=$(used)"
probe "승격된 사용자 상태(앞)" "$A/api/events/$E/queues/me" -H "X-Queue-Token: $T_FIRST"
probe "승격된 사용자 상태(뒤)" "$A/api/events/$E/queues/me" -H "X-Queue-Token: $T_LAST"

log "== 5. OOM인 채로 승격된 사용자의 좌석 홀드"
probe "홀드 1회차" -X POST "$A/api/events/$E/seats/hold" -H "Content-Type: application/json" -H "X-Queue-Token: $T_FIRST" -d "{\"seatIds\":[${SEATS[1]}],\"phoneNumber\":\"01000000002\"}"
probe "홀드 2회차(다른 사용자)" -X POST "$A/api/events/$E/seats/hold" -H "Content-Type: application/json" -H "X-Queue-Token: $T_MID" -d "{\"seatIds\":[${SEATS[2]}],\"phoneNumber\":\"01000000003\"}"
log "DB에 생긴 예약 수: $(psql_ -c "SELECT count(*) FROM reservation WHERE event_id=$E")"
probe "새 줄서기 등록" -X POST "$A/api/events/$E/queues"

log "== 6. 한도를 풀고 회복"
rcli CONFIG SET maxmemory 512mb > /dev/null
probe "새 줄서기 등록" -X POST "$A/api/events/$E/queues"
probe "홀드(풀린 뒤)" -X POST "$A/api/events/$E/seats/hold" -H "Content-Type: application/json" -H "X-Queue-Token: $T_FIRST" -d "{\"seatIds\":[${SEATS[1]}],\"phoneNumber\":\"01000000002\"}"
probe "헬스" "$A/actuator/health"

log "== 7. 홀드로 묶인 사용자 vs 아직 안 묶인 사용자(다시 OOM)"
make_oom
probe "묶인 사용자(T_FIRST)의 두 번째 홀드" -X POST "$A/api/events/$E/seats/hold" -H "Content-Type: application/json" -H "X-Queue-Token: $T_FIRST" -d "{\"seatIds\":[${SEATS[3]}],\"phoneNumber\":\"01000000002\"}"
probe "아직 안 묶인 사용자(T_LAST)의 홀드" -X POST "$A/api/events/$E/seats/hold" -H "Content-Type: application/json" -H "X-Queue-Token: $T_LAST" -d "{\"seatIds\":[${SEATS[4]}],\"phoneNumber\":\"01000000009\"}"
probe "활성 사용자 상태 조회" "$A/api/events/$E/queues/me" -H "X-Queue-Token: $T_FIRST"
probe "새 줄서기 등록" -X POST "$A/api/events/$E/queues"
probe "헬스" "$A/actuator/health"
log "DB 예약 수: $(psql_ -c "SELECT count(*) FROM reservation WHERE event_id=$E")"

log "== 앱 로그"
$COMPOSE logs --no-color app 2>/dev/null > "$OUT/app.log"
echo "WARN(한 줄) 503 로그 수: $(grep -c 'Redis를 일시적으로 쓸 수 없어 503' "$OUT/app.log")"
echo "OOM 문구가 든 로그 줄 수: $(grep -c 'OOM command not allowed' "$OUT/app.log"), 스택 트레이스가 붙은 예외 로그 줄 수: $(grep -c '처리되지 않은 예외 발생' "$OUT/app.log")"
echo "거부된 스크립트와 줄(해시, 줄 번호별 횟수):"; grep -o 'script: [0-9a-f]*, on @user_script:[0-9]*' "$OUT/app.log" | sort | uniq -c
grep -m1 'OOM command not allowed' "$OUT/app.log" | cut -c1-260
grep -m3 -E 'at com.ticketrush' "$OUT/app.log" | cut -c1-160
log "끝"
