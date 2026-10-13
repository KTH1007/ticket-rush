#!/bin/bash
# 대기열 부하 실측 실행기. 환경을 올리고, 단계를 순서대로 돌리고, 결과를 k6/results/<시각>/에 남기고, 환경을 정리한다.
#   STAGES="S1 S2 S3 S6 S4" ./k6/measure-queue.sh      (기본값 전체)
#   QUICK=1 ./k6/measure-queue.sh                      (규모를 줄여 스크립트만 검증)
#   KEEP=1 ...                                         (끝나도 환경을 지우지 않는다)
#   S1 등록 한계 곡선, S2 오픈 순간(키 유지와 오픈 순간 캐시 삭제), S3 메모리와 순번 폴링 용량(대기자 규모별),
#   S6 갑작스러운 폴링 폭주, S4 승격 중 폴링
#   N0 nginx를 거친 기준선(상한 끔), N1 nginx 상한 검증(상한 값별로 줄서기 과부하, 순번 조회 과부하, 폭주)
#   D1 대기열 뒤의 홀드와 결제가 DB를 어떻게 쓰는지(승격 속도 50, 100, 200/s와 좌석 분산, 집중). 줄서기부터 결제까지 한 사용자 흐름(k6/journey.js)
#   D2 홀드 도착 속도별 DB(활성 토큰으로 직접 보냄. 대기열 없는 대조군, 초당 100 ~ 600건, 앱 4코어로 앱 CPU 포화를 늦춰 DB 한계를 봄)
#   E1 대기 토큰 갱신(조회할 때마다 수명 연장)이 실제 앱에서 도는지. N3R 갱신을 넣은 뒤 순번 조회 처리량(N3 상한 250과 비교)
#   C2 출고 nginx 설정에서 재시작 직후의 차가운 앱이 과부하를 바로 받아도 사는지. P1 50만 명 20초 피크 모델을 줄서기 도착률로 그대로 재현
#   M2 속도 제한(limit_req)으로 조회나 줄서기가 앱 CPU를 다 쓰는 동안에도 홀드 몫이 남는지. A3 앱 2대에서 A2의 이유 모를 전면 실패를 다시 가림
#   Q1 대기자 수만큼 줄 세운 뒤 서버가 알려주는 조회 간격으로 초당 조회 요청 수를 계산. M4 그 요청 수로 조회를 보내며 홀드를 같이 보냄
#   F2 거절이 생기는 전체 흐름을 여러 번. D3 좌석 충돌이 몰릴 때 DB 연결 사용. PG 가짜 PG 응답 지연별 결제
#   N3A, N6A 변경 뒤 순번 조회(상한 250)와 줄서기(상한 500) 처리량 재측정
#   M3 홀드 지연이 받아준 요청 때문인지 거절당하는 요청 폭주 때문인지 가림
#   M1 순번 조회와 홀드, 결제가 같은 앱에서 동시에 오는 혼합 부하. A2 앱 2대 뒤에서 nginx 합계 상한 값
#   N6 줄서기 상한을 더 낮은 후보(250, 500, 1,000)로 12,000/s에서 비교(N4와 같은 방식)
#   F1 상한을 건 채 전체 흐름(줄서기, 순번 조회, 승격, 홀드, 결제)이 이어지는지. 거절이 실제로 생기도록 상한을 일부러 낮게 둔다
#   N5 nginx 자체의 처리 한계(앱을 거치지 않는 404 응답으로 입구가 초당 몇 건까지 받는지)
#   N4 줄서기 상한을 더 높은 후보(2,000, 3,000, 5,000)로 12,000/s에서 비교(단계마다 예열)
#   N3 순번 조회 상한을 더 낮은 후보(250, 500, 1,000)로 비교(단계마다 예열해서 재시작 직후의 차가운 앱 영향을 줄임)
#   N2 경계 부하에서 상한 후보 비교(줄서기와 순번 조회를 따로, 후보마다 N2_REPS번 반복, 단계 사이에 환경을 가라앉힘)
#   부하는 nginx(18080)로 보내고 헬스와 지표는 앱 직접 포트(18081)로 읽는다. 기본은 상한을 꺼서(QUEUE_LIMIT=65535, limit_conn이 받는 최댓값) S 단계가 앱 용량을 보게 한다
set -u
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f k6/docker-compose.measure.yml"
BASE=http://127.0.0.1:18080
ACTUATOR=http://127.0.0.1:18081
NO_LIMIT=65535
OUT="k6/results/$(date +%Y%m%d-%H%M%S)"
STAGES=${STAGES:-"S1 S2 S3 S6 S4"}
mkdir -p "$OUT"
ABS_OUT="$(pwd)/$OUT"
export PHONE_HMAC_KEY=${PHONE_HMAC_KEY:-$(openssl rand -base64 32)}
export PHONE_ENCRYPTION_KEY=${PHONE_ENCRYPTION_KEY:-$(openssl rand -base64 32)}

if [ "${QUICK:-0}" = "1" ]; then
  STEP=5; REG_RATES=200,400; POLL_RATES=500,1000; BURST_RATE=1000; BURST_DURATION=8s
  PREOPEN_RATE=300; PREOPEN_DURATION=24s; PREOPEN_WAIT=10
  N_SMALL=1000; N_MID=3000; N_BIG=5000; N_MAX=6000; S4_RATE=500; S4_DURATION=10s
  N0_REG_RATES=200,400; N0_POLL_RATES=500,1000
  N_LIMITS="1 150"; OVER_REG_RATES=400,800; OVER_POLL_RATES=500,1000,1500
  N2_LIMITS="65535 1 150"; N2_REPS=2; N2_REG_RATE=800; N2_POLL_RATE=1500; SETTLE=3
  N3_LIMITS="65535 100"; N3_REPS=2
  N4_LIMITS="1 150"; N4_REPS=2; N4_REG_RATE=1000
  N5_RATES=1000,2000
  N6_LIMITS="1 150"
  F1_USERS=200; F1_SPREAD=1; F1_REG_LIMIT=2; F1_POLL_LIMIT=2; F1_WARM_USERS=30
  D1_USERS=200; D1_THINK=1; D1_CASES="50:wide 100:tiny"; D1_HOT_POOL=100; D1_TINY_POOL=5; D1_WARM_USERS=30; D1_WAIT=120
  D2_CASES="rate:50:4:2 burst:200:1:2"
  E1_TTL=10; E1_POLL_S=3; E1_ROUNDS=6; E1_COUNT=20
  C2_REPS=1; P1_SCALE=0.01
  M1_CASES="0:20 300:20"; M1_HOLD_SECONDS=5; M1_POLL_TOKENS=500
  A2_REG_RATE=1000; A2_REG_LIMITS="1 150"; A2_POLL_RATE=1000; A2_POLL_LIMITS="1 150"; A2_REPS=1
  M2_HOLD_RATE=20; M2_CASES="poll:300:0:100 register:300:100:0"
  M3_CASES="poll:150:0:0 poll:300:0:30"
  Q1_USERS=5000; Q1_SAMPLES=100; M4_FALLBACK_RATE=1500; F2_REPS=1
  D3_RATE=20; D3_SECONDS=5; D3_POOL=5; D3_REPS=1; PG_DELAYS="0 300"; PG_RATE=20; PG_SECONDS=5
  A3_REG_RATES="500"; A3_REPS=1
else
  STEP=30; REG_RATES=500,1000,2000,4000,8000; POLL_RATES=1000,2000,5000,10000,15000
  BURST_RATE=10000; BURST_DURATION=20s
  PREOPEN_RATE=2000; PREOPEN_DURATION=80s; PREOPEN_WAIT=40
  N_SMALL=10000; N_MID=100000; N_BIG=300000; N_MAX=500000; S4_RATE=5000; S4_DURATION=60s
  N0_REG_RATES=500,1000,2000,4000; N0_POLL_RATES=1000,2000,5000
  N_LIMITS="500 1000 2000"; OVER_REG_RATES=4000,8000,12000; OVER_POLL_RATES=5000,10000,15000
  N2_LIMITS="65535 1000 1500 2000"; N2_REPS=3; N2_REG_RATE=8000; N2_POLL_RATE=10000; SETTLE=20
  N3_LIMITS="50 100 250"; N3_REPS=3
  N4_LIMITS="2000 3000 5000"; N4_REPS=3; N4_REG_RATE=12000
  N5_RATES=10000,20000,30000,40000
  N6_LIMITS="250 500 1000"
  F1_USERS=3000; F1_SPREAD=2; F1_REG_LIMIT=3; F1_POLL_LIMIT=2; F1_WARM_USERS=150
  D1_USERS=3000; D1_THINK=3; D1_CASES="50:wide 100:wide 200:wide 100:hot 100:tiny"; D1_HOT_POOL=3000; D1_TINY_POOL=30; D1_WARM_USERS=150; D1_WAIT=300
  # rate:초당 건수:초:앱 코어(홀드 도착 속도를 정함), burst:사용자 수:퍼뜨리는 초:앱 코어(대기열 없는 대조군). 건수를 3,000이나 6,000으로 맞춰 충돌 비율을 비슷하게 한다
  D2_CASES="rate:100:30:2 burst:3000:2:2 rate:200:30:4 rate:300:20:4 rate:400:15:4 rate:600:10:4"
  E1_TTL=30; E1_POLL_S=10; E1_ROUNDS=10; E1_COUNT=200
  C2_REPS=3; P1_SCALE=1
  M1_CASES="0:100 5000:100 10000:100 10000:200"; M1_HOLD_SECONDS=30; M1_POLL_TOKENS=10000
  A2_REG_RATE=24000; A2_REG_LIMITS="500 1000 2000"; A2_POLL_RATE=20000; A2_POLL_LIMITS="250 500 1000"; A2_REPS=2
  M3_CASES="poll:1500:0:0 poll:3000:0:0 poll:10000:0:100 register:3000:0:0 register:12000:100:0"
  Q1_USERS=500000; Q1_SAMPLES=1000; M4_FALLBACK_RATE=33000; F2_REPS=3
  D3_RATE=200; D3_SECONDS=15; D3_POOL=30; D3_REPS=2; PG_DELAYS="0 300 1000"; PG_RATE=100; PG_SECONDS=30
  M2_HOLD_RATE=100; M2_CASES="poll:10000:0:1500 poll:10000:0:2500 poll:10000:0:3500 register:12000:0:0 register:12000:3000:0 register:12000:5000:0"
  A3_REG_RATES="16000 24000"; A3_REPS=3
fi
GAP=5

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }
rcli() { $COMPOSE exec -T redis redis-cli "$@" | tr -d '\r'; }
psql_() { $COMPOSE exec -T postgres psql -U ticketrush -d ticketrush -tA "$@"; }

cleanup() {
  [ -n "${STATS_PID:-}" ] && kill "$STATS_PID" 2>/dev/null
  [ -n "${APP_STATS_PID:-}" ] && kill "$APP_STATS_PID" 2>/dev/null
  [ -n "${DB_STATS_PID:-}" ] && kill "$DB_STATS_PID" 2>/dev/null
  if [ "${KEEP:-0}" != "1" ]; then
    $COMPOSE --profile two down -v --remove-orphans >/dev/null 2>&1
    docker rmi tr-measure-app >/dev/null 2>&1
  fi
}
trap cleanup EXIT

CURRENT_PROMO=1h

up() { # 승격 주기(1h면 꺼진 것과 같다)
  CURRENT_PROMO="$1"
  # 환경변수를 export로 계속 유지한다. 다음 compose 호출(nginx 재생성 등)에서 값이 달라 보이면 compose가 앱을 기본값으로 다시 만든다
  export QUEUE_PROMOTION_INTERVAL="$1"
  $COMPOSE up -d --build app >> "$OUT/compose.log" 2>&1
  for _ in $(seq 1 120); do
    if curl -s -m 2 "$ACTUATOR/actuator/health/readiness" | grep -q '"UP"'; then restart_nginx; return 0; fi
    sleep 2
  done
  log "앱이 뜨지 않음"; return 1
}

# nginx는 시작할 때 앱 주소를 한 번만 읽는다. 앱이 다시 뜨면 주소가 바뀔 수 있어서 nginx도 다시 만든다
restart_nginx() {
  $COMPOSE up -d --force-recreate nginx >> "$OUT/compose.log" 2>&1
  for _ in $(seq 1 30); do
    [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "$BASE/actuator/health")" = "404" ] && return 0
    sleep 1
  done
  log "nginx가 뜨지 않음"; return 1
}

# 줄서기와 순번 조회의 동시 처리 상한(둘째를 생략하면 같은 값). 공유 설정(docker/nginx/nginx.conf)에서 두 줄의 숫자만 바꾼 복사본을 쓴다
nginx_limit() { # 줄서기 [순번 조회]
  local reg=$1 poll=${2:-$1}
  sed -E -e "s/(limit_conn queue_register )[0-9]+;/\\1${reg};/" -e "s/(limit_conn queue_poll )[0-9]+;/\\1${poll};/" docker/nginx/nginx.conf > "$ABS_OUT/nginx.conf"
  export NGINX_CONF="$ABS_OUT/nginx.conf"
  restart_nginx || { log "nginx를 띄우지 못해 측정을 멈춤"; exit 1; }
  log "nginx 상한: 줄서기 $reg, 순번 조회 $poll"
}

settle() { # 앞 단계의 연결 정리(TIME_WAIT)가 끝나길 기다린다. 포트가 남아 있으면 다음 단계의 부하기가 연결을 못 열어 서버와 무관하게 실패한다(A2)
  sleep "${SETTLE:-20}"
  for _ in $(seq 1 60); do
    [ "$(netstat -an -p tcp | grep -c TIME_WAIT)" -lt 2000 ] && break
    sleep 2
  done
}

seed() {
  psql_ < scripts/seed-event.sql > /dev/null
  EVENT_ID=$(psql_ -c "SELECT id FROM event ORDER BY id DESC LIMIT 1")
  log "공연 $EVENT_ID, 좌석 $(psql_ -c "SELECT count(*) FROM seat WHERE event_id=$EVENT_ID")석"
}

warmup() { # 결과에 넣지 않는 짧은 예열. JVM이 식기 전의 첫 단계가 느리게 나오는 것을 막는다
  k6 run --quiet -e BASE=$BASE -e EVENT_ID="$EVENT_ID" "$@" "${K6_SCRIPT:-k6/queue.js}" > /dev/null 2>&1
}

reset_queue() { rcli FLUSHALL > /dev/null; }
open_event() { psql_ -c "UPDATE event SET opens_at = now() - interval '1 hour' WHERE id=$EVENT_ID" > /dev/null; rcli DEL "queue:$EVENT_ID:opensAt" > /dev/null; }

# 과부하로 앱이 죽었는지 확인하고, 죽었으면 상태(OOM kill 여부, 종료 코드)와 마지막 로그를 남기고 다시 띄운다.
# 응답이 멎었다가 곧 죽는 경우가 있어서, 응답이 돌아올 때까지 기다리며 죽을 때마다 다시 띄운다.
# 운영의 오케스트레이터가 재시작해 주는 것을 흉내 내는 것이고, 응답하지 못한 시간도 기록한다
check_app() { # 단계 이름
  local cid state detail started crashes=0 waited
  started=$(date +%s)
  while ! curl -s -m 3 "$ACTUATOR/actuator/health/readiness" | grep -q '"UP"'; do
    cid=$($COMPOSE ps -aq app)
    state=$(docker inspect -f '{{.State.Status}}' "$cid")
    if [ "$state" != "running" ]; then
      crashes=$(( crashes + 1 ))
      detail=$(docker inspect -f '상태={{.State.Status}} OOM_kill={{.State.OOMKilled}} 종료코드={{.State.ExitCode}}' "$cid")
      docker logs --tail 80 "$cid" > "$OUT/crash-$1-$crashes.log" 2>&1
      log "앱이 죽음 ($1, ${crashes}번째): $detail"
      echo "- **앱이 죽음 ($1, ${crashes}번째)**: $detail. 마지막 로그는 crash-$1-$crashes.log" | tee -a "$OUT/results.md"
      QUEUE_PROMOTION_INTERVAL="$CURRENT_PROMO" $COMPOSE up -d app >> "$OUT/compose.log" 2>&1
    fi
    if [ $(( $(date +%s) - started )) -gt 600 ]; then log "앱이 600초 안에 복구되지 않음"; return 1; fi
    sleep 3
  done
  waited=$(( $(date +%s) - started ))
  [ "$crashes" -gt 0 ] && restart_nginx
  if [ "$waited" -gt 5 ] || [ "$crashes" -gt 0 ]; then
    echo "- $1 직후 앱이 ${waited}초 동안 응답하지 못했고 ${crashes}번 죽었다. 이 단계의 GC와 Hikari 차이는 재시작 때문에 무효" | tee -a "$OUT/results.md"
    touch "$OUT/$1.crashed"
  fi
}

snap() { # 출력 파일
  local mem keys cs calls usec xact prom hk gcs gcc acq
  mem=$(rcli INFO memory | awk -F: '/^used_memory:/{print $2}')
  keys=$(rcli DBSIZE | awk '{print $1}')
  # calls=와 usec=만 정확히 읽는다. sed의 .*는 rejected_calls=0까지 삼켜 호출 수가 0으로 나온다
  cs=$(rcli INFO commandstats | grep -E '^cmdstat_eval(sha)?:' | awk -F'[:,]' '{for (i = 1; i <= NF; i++) { if ($i ~ /^calls=/) { sub("calls=", "", $i); c += $i } if ($i ~ /^usec=/) { sub("usec=", "", $i); u += $i } }} END {print c + 0, u + 0}')
  calls=${cs% *}
  usec=${cs#* }
  xact=$(psql_ -c "SELECT xact_commit FROM pg_stat_database WHERE datname='ticketrush'")
  prom=$(curl -s "$ACTUATOR/actuator/prometheus")
  hk=$(echo "$prom" | awk '/^hikaricp_connections_timeout_total/{print $NF}' | head -1)
  gcs=$(echo "$prom" | awk '/^jvm_gc_pause_seconds_sum/{s+=$NF} END{print s+0}')
  gcc=$(echo "$prom" | awk '/^jvm_gc_pause_seconds_count/{s+=$NF} END{print s+0}')
  acq=$(echo "$prom" | awk '/^hikaricp_connections_acquire_seconds_count/{print $NF}' | head -1)
  printf '{"t":%s,"redis_used_memory":%s,"redis_keys":%s,"evalsha_calls":%s,"evalsha_usec":%s,"pg_xact":%s,"hikari_timeout":%s,"gc_sum":%s,"gc_count":%s,"hikari_acquire":%s}\n' \
    "$(date +%s)" "${mem:-0}" "${keys:-0}" "${calls:-0}" "${usec:-0}" "${xact:-0}" "${hk:-0}" "${gcs:-0}" "${gcc:-0}" "${acq:-0}" > "$1"
}

start_stats() { # 출력 파일
  (
    while true; do
      now=$(date +%s)
      docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' $($COMPOSE ps -q) 2>/dev/null | awk -F'|' -v t="$now" '{
        role = "other"
        if ($1 ~ /nginx/) role = "nginx"; else if ($1 ~ /app/) role = "app"; else if ($1 ~ /redis/) role = "redis"; else if ($1 ~ /postgres/) role = "postgres"
        cpu = $2; gsub("%", "", cpu)
        split($3, a, " / "); v = a[1] + 0
        if (a[1] ~ /GiB/) v = v * 1024; else if (a[1] ~ /KiB/) v = v / 1024
        printf "%s\t%s\t%s\t%.1f\n", t, role, cpu, v }' >> "$1"
    done
  ) &
  STATS_PID=$!
}
start_app_stats() { # 출력 파일: 시각, 힙 사용(MiB), 처리 중인 요청 수. 앱이 죽어 있으면 기록하지 않는다
  (
    while true; do
      curl -s -m 2 "$ACTUATOR/actuator/prometheus" | awk -v t="$(date +%s)" '
        /^jvm_memory_used_bytes\{area="heap"/ { h += $NF }
        /^http_server_requests_active_seconds_count/ { a = $NF }
        END { if (h > 0) printf "%s\t%.0f\t%s\n", t, h / 1048576, a + 0 }' >> "$1"
      sleep 1
    done
  ) &
  APP_STATS_PID=$!
}
start_db_stats() { # 출력 파일: 시각, Hikari 사용 중 연결, 연결을 기다리는 스레드, Postgres 실행 중 쿼리
  (
    while true; do
      read -r hk_active hk_pending < <(curl -s -m 2 "$ACTUATOR/actuator/prometheus" | awk '/^hikaricp_connections_active/{a = $NF} /^hikaricp_connections_pending/{p = $NF} END {print a + 0, p + 0}')
      pg=$(psql_ -c "SELECT count(*) FROM pg_stat_activity WHERE datname='ticketrush' AND state='active' AND pid <> pg_backend_pid()" 2>/dev/null)
      printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "${hk_active:-0}" "${hk_pending:-0}" "${pg:-0}" >> "$1"
      sleep 1
    done
  ) &
  DB_STATS_PID=$!
}
stop_db_stats() { [ -n "${DB_STATS_PID:-}" ] && kill "$DB_STATS_PID" 2>/dev/null; wait "${DB_STATS_PID:-}" 2>/dev/null; DB_STATS_PID=""; }
stop_app_stats() { [ -n "${APP_STATS_PID:-}" ] && kill "$APP_STATS_PID" 2>/dev/null; wait "$APP_STATS_PID" 2>/dev/null; APP_STATS_PID=""; }
stop_stats() { [ -n "${STATS_PID:-}" ] && kill "$STATS_PID" 2>/dev/null; wait "$STATS_PID" 2>/dev/null; STATS_PID=""; }

k6run() { # 이름 [--out 인자들은 K6_EXTRA] -e 환경변수들
  local name=$1; shift
  check_app "$name-시작전"
  date +%s > "$OUT/$name.t0"
  snap "$OUT/$name.before.json"
  start_stats "$OUT/$name.stats.tsv"
  start_app_stats "$OUT/$name.app.tsv"
  [ "${DB_STATS:-0}" = "1" ] && start_db_stats "$OUT/$name.db.tsv"
  # shellcheck disable=SC2086
  k6 run --quiet --summary-export="$OUT/$name.summary.json" ${K6_EXTRA:-} \
    -e BASE=$BASE -e EVENT_ID="$EVENT_ID" "$@" "${K6_SCRIPT:-k6/queue.js}" > "$OUT/$name.k6.log" 2>&1
  log "$name k6 종료 코드 $?"
  stop_stats
  stop_app_stats
  stop_db_stats
  check_app "$name"
  snap "$OUT/$name.after.json"
}

report_steps() { # 이름 접두사 도착률 단계초
  python3 k6/summarize-queue.py steps "$OUT/$1.summary.json" "$2" "$3" "$4" "$GAP" "$OUT/$1.stats.tsv" "$(cat "$OUT/$1.t0")" "$OUT/$1.app.tsv" | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py snapdiff "$OUT/$1.before.json" "$OUT/$1.after.json" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

report_limit_steps() { # 이름 접두사 도착률 단계초
  python3 k6/summarize-queue.py limit "$OUT/$1.summary.json" "$2" "$3" "$4" "$GAP" "$OUT/$1.stats.tsv" "$(cat "$OUT/$1.t0")" "$OUT/$1.app.tsv" | tee -a "$OUT/results.md"
  if [ -f "$OUT/$1.crashed" ]; then echo "- **앱 생존: 죽음**" | tee -a "$OUT/results.md"; else echo "- 앱 생존: 죽지 않음" | tee -a "$OUT/results.md"; fi
  python3 k6/summarize-queue.py snapdiff "$OUT/$1.before.json" "$OUT/$1.after.json" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

nginx_errors() { # 상한 값
  local count
  count=$($COMPOSE logs --no-color nginx 2>/dev/null | grep -c "worker_connections are not enough")
  echo "- nginx 상한 $1: worker_connections 부족 로그 ${count}건" | tee -a "$OUT/results.md"
}

fill_to() { # 목표 등록 수. nginx를 막 다시 띄운 직후에는 연결이 끊겨(connection reset) 덜 채워질 수 있어 모자라면 다시 채운다
  local now need try
  for try in 1 2 3 4 5; do
    now=$(rcli ZCARD "queue:$EVENT_ID:waiting")
    need=$(( $1 - now ))
    [ "$need" -gt 0 ] || break
    k6run "fill_${1}_$try" -e SCENARIO=fill -e COUNT="$need" -e VUS=$(( need < 100 ? need : 100 ))
  done
  log "등록 $1명 채움: ZCARD $(rcli ZCARD "queue:$EVENT_ID:waiting"), used_memory $(( $(rcli INFO memory | awk -F: '/^used_memory:/{print $2}') / 1048576 ))MiB"
}

memory_row() { # 대기자 수
  local used rss keys
  used=$(rcli INFO memory | awk -F: '/^used_memory:/{print $2}')
  rss=$(rcli INFO memory | awk -F: '/^used_memory_rss:/{print $2}')
  keys=$(rcli DBSIZE | awk '{print $1}')
  python3 - "$1" "$used" "$rss" "$keys" <<'PY' | tee -a "$OUT/results.md"
import sys
n, used, rss, keys = (int(x) for x in sys.argv[1:5])
print(f"| {n:,}명 | {used / 1048576:.0f}MiB | {rss / 1048576:.0f}MiB | {keys:,} | {used / n:.0f}바이트 | {used / (512 * 1048576) * 100:.0f}% |")
PY
}

dump_tokens() { # 개수 파일
  rcli ZRANGE "queue:$EVENT_ID:waiting" 0 $(( $1 - 1 )) > "$2"
  log "토큰 $(wc -l < "$2") 개를 $2에 저장"
}

env_info() {
  local app
  app=$($COMPOSE ps -q app)
  {
    echo "- 호스트: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu)코어, $(( $(sysctl -n hw.memsize) / 1073741824 ))GB, macOS $(sw_vers -productVersion)"
    echo "- Docker $(docker version --format '{{.Server.Version}}'), VM $(docker info --format 'CPU {{.NCPU}}개, 메모리 {{.MemTotal}}바이트')"
    echo "- k6 $(k6 version | head -1 | awk '{print $2}'), 부하기는 호스트에서 실행(Docker 포트 포워딩 경유)"
    echo "- 코드: $(git rev-parse --short HEAD) ($(git branch --show-current))"
    echo "- 앱 컨테이너 한도: CPU $(docker inspect -f '{{.HostConfig.NanoCpus}}' "$app" | awk '{print $1 / 1e9}')코어, 메모리 $(docker inspect -f '{{.HostConfig.Memory}}' "$app" | awk '{print $1 / 1073741824}')GiB"
    echo "- 앱 JVM: $($COMPOSE exec -T app java -XX:+PrintFlagsFinal -version 2>/dev/null | awk '/ MaxHeapSize |UseG1GC |ParallelGCThreads |ConcGCThreads /{printf "%s=%s ", $2, $4}')"
    echo "- nginx $($COMPOSE exec -T nginx nginx -v 2>&1 | awk -F/ '{print $2}'), 앱 1대 upstream, 설정은 docker/nginx/nginx.conf(줄서기 상한 숫자만 단계별로 바꿈, 기본은 꺼 둠)"
    echo "- Redis $(rcli INFO server | awk -F: '/^redis_version:/{print $2}'), maxmemory $(rcli CONFIG GET maxmemory | tail -1) ($(rcli CONFIG GET maxmemory-policy | tail -1)), CPU 1코어"
    echo "- Postgres $(psql_ -c 'SHOW server_version'), CPU 2코어, 메모리 1GiB"
    echo "- 토큰 TTL 2시간(측정이 15분을 넘겨도 미리 등록한 토큰이 만료되지 않게 함), 승격은 S4를 뺀 단계에서 끔"
  } > "$OUT/env.md"
  { echo "## 환경"; cat "$OUT/env.md"; echo; echo "## 결과"; echo; } >> "$OUT/results.md"
}

stage_S1() {
  log "S1 등록 한계 곡선"; open_event
  warmup -e SCENARIO=fill -e COUNT=$([ "${QUICK:-0}" = "1" ] && echo 500 || echo 5000) -e VUS=50
  reset_queue; open_event
  echo "### S1 등록 한계 곡선 (열린 공연, 단계 ${STEP}초)" >> "$OUT/results.md"
  k6run S1 -e SCENARIO=register -e RATES=$REG_RATES -e STEP_SECONDS=$STEP
  report_steps S1 reg $REG_RATES "$STEP"
}

stage_S2() {
  local variant open_epoch
  for variant in a b; do
    log "S2$variant 오픈 순간 ($([ $variant = a ] && echo '캐시 유지' || echo '오픈 순간에 캐시 삭제'))"
    reset_queue
    open_epoch=$(( $(date +%s) + PREOPEN_WAIT ))
    psql_ -c "UPDATE event SET opens_at = to_timestamp($open_epoch) WHERE id=$EVENT_ID" > /dev/null
    rcli DEL "queue:$EVENT_ID:opensAt" > /dev/null
    if [ $variant = b ]; then
      ( sleep $(( open_epoch - $(date +%s) )); rcli DEL "queue:$EVENT_ID:opensAt" > /dev/null ) &
    fi
    K6_EXTRA="--out csv=$OUT/S2$variant.csv" k6run "S2$variant" -e SCENARIO=preopen -e RATE=$PREOPEN_RATE -e DURATION=$PREOPEN_DURATION
    echo "### S2$variant 오픈 순간 ($([ $variant = a ] && echo '오픈 시각 캐시 유지' || echo '오픈 순간에 캐시 삭제'), 초당 ${PREOPEN_RATE}건, 오픈 시각 = 0초)" >> "$OUT/results.md"
    python3 k6/summarize-queue.py seconds "$OUT/S2$variant.csv" -6 8 "$open_epoch" | tee -a "$OUT/results.md"
    python3 k6/summarize-queue.py snapdiff "$OUT/S2$variant.before.json" "$OUT/S2$variant.after.json" | tee -a "$OUT/results.md"
    echo >> "$OUT/results.md"
    rm -f "$OUT/S2$variant.csv"
  done
}

stage_S3() {
  log "S3 메모리와 순번 폴링 용량"; reset_queue; open_event
  {
    echo "### S3 Redis 메모리 (등록 누적, 토큰 TTL 2시간)"
    echo "| 대기자 | used_memory | RSS | 키 수 | 1인당 | maxmemory(512MiB) 대비 |"
    echo "|---|---|---|---|---|---|"
  } >> "$OUT/results.md"
  fill_to $N_SMALL; memory_row $N_SMALL
  dump_tokens $N_SMALL "$ABS_OUT/tokens-small.txt"
  warmup -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-small.txt" -e POLL_RATES=1000 -e STEP_SECONDS=10
  echo -e "\n### S3 순번 폴링 용량 (대기자 ${N_SMALL}명, 단계 ${STEP}초)" >> "$OUT/results.md"
  k6run S3_small -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-small.txt" -e POLL_RATES=$POLL_RATES -e STEP_SECONDS=$STEP
  report_steps S3_small poll $POLL_RATES "$STEP"
  fill_to $N_MID; memory_row $N_MID
  dump_tokens $N_MID "$ABS_OUT/tokens-mid.txt"
  echo -e "\n### S3 순번 폴링 용량 (대기자 ${N_MID}명, 단계 ${STEP}초)" >> "$OUT/results.md"
  k6run S3_mid -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-mid.txt" -e POLL_RATES=$POLL_RATES -e STEP_SECONDS=$STEP
  report_steps S3_mid poll $POLL_RATES "$STEP"
  fill_to $N_BIG; memory_row $N_BIG
  fill_to $N_MAX; memory_row $N_MAX
  dump_tokens $N_MAX "$ABS_OUT/tokens-max.txt"
  echo -e "\n### S3 순번 폴링 (대기자 ${N_MAX}명, 한 단계, 큐 크기 영향 확인)" >> "$OUT/results.md"
  k6run S3_max -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-max.txt" -e POLL_RATES=$S4_RATE -e STEP_SECONDS=$STEP
  report_steps S3_max poll $S4_RATE "$STEP"
}

stage_S6() {
  log "S6 갑작스러운 폴링 폭주"
  warmup -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-mid.txt" -e POLL_RATES=1000 -e STEP_SECONDS=5
  K6_EXTRA="--out csv=$OUT/S6.csv" k6run S6 -e SCENARIO=burst -e TOKENS_FILE="$ABS_OUT/tokens-mid.txt" -e RATE=$BURST_RATE -e DURATION=$BURST_DURATION
  echo -e "\n### S6 폴링 폭주 (대기자 ${N_MID}명, 도착률 ${BURST_RATE}/s가 0초에 갑자기 시작)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py seconds "$OUT/S6.csv" 0 12 | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py snapdiff "$OUT/S6.before.json" "$OUT/S6.after.json" | tee -a "$OUT/results.md"
  rm -f "$OUT/S6.csv"
}

stage_S4() {
  log "S4 승격 중 폴링 (승격 주기 1초로 앱을 다시 띄움)"
  up 1s || return 1
  warmup -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-mid.txt" -e POLL_RATES=2000 -e STEP_SECONDS=10
  local promoted_before
  promoted_before=$(rcli GET "queue:$EVENT_ID:lastPromotedSeq")
  ( $COMPOSE exec -T redis redis-cli --latency-history -i 5 > "$OUT/S4.latency.txt" 2>&1 ) &
  local lat_pid=$!
  echo -e "\n### S4 승격 100/s가 도는 동안의 폴링 (대기자 ${N_MID}명 토큰, 도착률 ${S4_RATE}/s ${S4_DURATION})" >> "$OUT/results.md"
  k6run S4 -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-mid.txt" -e POLL_RATES=$S4_RATE -e STEP_SECONDS=${S4_DURATION%s}
  kill "$lat_pid" 2>/dev/null; $COMPOSE exec -T redis pkill redis-cli 2>/dev/null
  report_steps S4 poll $S4_RATE "${S4_DURATION%s}"
  echo "- 승격된 마지막 순번 ${promoted_before:-0} -> $(rcli GET "queue:$EVENT_ID:lastPromotedSeq")" | tee -a "$OUT/results.md"
  awk '{ if ($2 > m) m = $2; s += $3; n++ } END { if (n) printf "- Redis 응답 지연(redis-cli --latency-history, %d회 표본): 평균 %.3fms, 최대 %.3fms\n", n, s / n, m }' "$OUT/S4.latency.txt" | tee -a "$OUT/results.md"
}

stage_N0() {
  log "N0 nginx를 거친 기준선 (상한 끔)"; nginx_limit $NO_LIMIT
  warmup -e SCENARIO=fill -e COUNT=$([ "${QUICK:-0}" = "1" ] && echo 500 || echo 5000) -e VUS=50
  reset_queue; open_event
  echo "### N0 nginx를 거친 줄서기 (상한 끔, 단계 ${STEP}초)" >> "$OUT/results.md"
  k6run N0_reg -e SCENARIO=register -e RATES=$N0_REG_RATES -e STEP_SECONDS=$STEP
  report_limit_steps N0_reg reg $N0_REG_RATES "$STEP"
  fill_to $N_SMALL
  dump_tokens $N_SMALL "$ABS_OUT/tokens-n0.txt"
  echo "### N0 nginx를 거친 순번 조회 (상한 끔, 대기자 ${N_SMALL}명, 단계 ${STEP}초)" >> "$OUT/results.md"
  k6run N0_poll -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-n0.txt" -e POLL_RATES=$N0_POLL_RATES -e STEP_SECONDS=$STEP
  report_limit_steps N0_poll poll $N0_POLL_RATES "$STEP"
}

stage_N1() {
  local limit n
  nginx_limit $NO_LIMIT
  warmup -e SCENARIO=fill -e COUNT=$([ "${QUICK:-0}" = "1" ] && echo 500 || echo 5000) -e VUS=50
  for limit in $N_LIMITS; do
    log "N1 줄서기와 순번 조회 상한 $limit"; nginx_limit "$limit"
    reset_queue; open_event
    echo "### N1 상한 ${limit}: 줄서기 과부하 (단계 ${STEP}초)" >> "$OUT/results.md"
    k6run "N1_reg_$limit" -e SCENARIO=register -e RATES=$OVER_REG_RATES -e STEP_SECONDS=$STEP
    report_limit_steps "N1_reg_$limit" reg $OVER_REG_RATES "$STEP"
    n=$(rcli ZCARD "queue:$EVENT_ID:waiting"); [ "$n" -gt "$N_SMALL" ] && n=$N_SMALL
    dump_tokens "$n" "$ABS_OUT/tokens-n1-$limit.txt"
    echo "### N1 상한 ${limit}: 순번 조회 과부하 (대기자 ${n}명 토큰, 단계 ${STEP}초)" >> "$OUT/results.md"
    k6run "N1_poll_$limit" -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-n1-$limit.txt" -e POLL_RATES=$OVER_POLL_RATES -e STEP_SECONDS=$STEP
    report_limit_steps "N1_poll_$limit" poll $OVER_POLL_RATES "$STEP"
    K6_EXTRA="--out csv=$OUT/N1_burst_$limit.csv" k6run "N1_burst_$limit" -e SCENARIO=burst -e TOKENS_FILE="$ABS_OUT/tokens-n1-$limit.txt" -e RATE=$BURST_RATE -e DURATION=$BURST_DURATION
    echo "### N1 상한 ${limit}: 순번 조회 폭주 (도착률 ${BURST_RATE}/s가 0초에 갑자기 시작)" >> "$OUT/results.md"
    python3 k6/summarize-queue.py seconds "$OUT/N1_burst_$limit.csv" 0 12 | tee -a "$OUT/results.md"
    if [ -f "$OUT/N1_burst_$limit.crashed" ]; then echo "- **앱 생존: 죽음**" | tee -a "$OUT/results.md"; else echo "- 앱 생존: 죽지 않음" | tee -a "$OUT/results.md"; fi
    rm -f "$OUT/N1_burst_$limit.csv"
    nginx_errors "$limit"
    echo >> "$OUT/results.md"
  done
}

stage_N2() {
  local limit rep
  nginx_limit $NO_LIMIT
  warmup -e SCENARIO=fill -e COUNT=$([ "${QUICK:-0}" = "1" ] && echo 500 || echo 5000) -e VUS=50
  for rep in $(seq 1 "$N2_REPS"); do
    for limit in $N2_LIMITS; do
      log "N2 줄서기 ${N2_REG_RATE}/s 상한 $limit (${rep}번째)"
      settle; nginx_limit "$limit" $NO_LIMIT; reset_queue; open_event
      k6run "N2_reg_${limit}_$rep" -e SCENARIO=register -e RATES=$N2_REG_RATE -e STEP_SECONDS=$STEP
    done
  done
  echo "### N2 줄서기 경계 부하 ${N2_REG_RATE}/s, 상한 후보별 ${N2_REPS}번 (단계 ${STEP}초, 순번 조회 풀은 끔)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "N2_reg_{limit}_{rep}" reg "$N2_REG_RATE" "${N2_LIMITS// /,}" "$N2_REPS" "$STEP" | tee -a "$OUT/results.md"
  echo >> "$OUT/results.md"
  nginx_limit $NO_LIMIT; reset_queue; open_event
  fill_to $N_SMALL; dump_tokens $N_SMALL "$ABS_OUT/tokens-n2.txt"
  for rep in $(seq 1 "$N2_REPS"); do
    for limit in $N2_LIMITS; do
      log "N2 순번 조회 ${N2_POLL_RATE}/s 상한 $limit (${rep}번째)"
      settle; nginx_limit $NO_LIMIT "$limit"
      k6run "N2_poll_${limit}_$rep" -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-n2.txt" -e POLL_RATES=$N2_POLL_RATE -e STEP_SECONDS=$STEP
    done
  done
  echo "### N2 순번 조회 경계 부하 ${N2_POLL_RATE}/s, 상한 후보별 ${N2_REPS}번 (대기자 ${N_SMALL}명 토큰, 단계 ${STEP}초, 줄서기 풀은 끔)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "N2_poll_{limit}_{rep}" poll "$N2_POLL_RATE" "${N2_LIMITS// /,}" "$N2_REPS" "$STEP" | tee -a "$OUT/results.md"
  echo >> "$OUT/results.md"
}

stage_F1() {
  local alive seats
  export QUEUE_PROMOTION_BUDGET=100
  up 1s || return 1
  nginx_limit $NO_LIMIT
  reset_queue; seed; open_event
  seats="$ABS_OUT/seats-f1.txt"
  psql_ -c "SELECT id FROM seat WHERE event_id=$EVENT_ID ORDER BY id" > "$seats"
  K6_SCRIPT=k6/journey.js warmup -e USERS=$F1_WARM_USERS -e SEATS_FILE="$seats" -e THINK=1 -e PHONE_BASE=90000000 -e WAIT_LIMIT=60
  nginx_limit "$F1_REG_LIMIT" "$F1_POLL_LIMIT"
  echo -e "\n### F1 상한을 건 채 전체 흐름 (줄서기 상한 ${F1_REG_LIMIT}, 순번 조회 상한 ${F1_POLL_LIMIT}은 거절을 일부러 만들려고 낮춘 값, 사용자 ${F1_USERS}명을 ${F1_SPREAD}초 안에 접속, 승격 100/s, 홀드와 결제는 상한 대상이 아님)" >> "$OUT/results.md"
  DB_STATS=1 K6_SCRIPT=k6/journey.js k6run F1 -e USERS=$F1_USERS -e SEATS_FILE="$seats" -e THINK=3 -e PHONE_BASE=0 -e REG_SPREAD=$F1_SPREAD -e WAIT_LIMIT=300
  [ -f "$OUT/F1.crashed" ] && alive=no || alive=yes
  python3 k6/summarize-db.py header | tee -a "$OUT/results.md"
  python3 k6/summarize-db.py row "F1 상한 건 전체 흐름" "$OUT/F1.summary.json" "$OUT/F1.stats.tsv" "$OUT/F1.db.tsv" $alive | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py snapdiff "$OUT/F1.before.json" "$OUT/F1.after.json" | tee -a "$OUT/results.md"
  echo "- 예약 상태별: $(psql_ -c "SELECT string_agg(status || ' ' || n, ', ') FROM (SELECT status, count(*) n FROM reservation WHERE event_id=$EVENT_ID GROUP BY status) t")" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

stage_N5() {
  nginx_limit $NO_LIMIT
  echo "### N5 nginx 자체의 처리 한계 (/actuator/ 는 nginx가 직접 404로 응답, 앱과 Redis를 거치지 않음, 단계 ${STEP}초, 부하기와 같은 머신이라 하한)" >> "$OUT/results.md"
  K6_SCRIPT=k6/nginx-raw.js k6run N5 -e RATES=$N5_RATES -e STEP_SECONDS=$STEP
  report_limit_steps N5 raw $N5_RATES "$STEP"
}

stage_N4() { # N6도 이 함수를 쓴다. N4_TAG로 단계 이름을 바꾼다
  local limit rep
  for rep in $(seq 1 "$N4_REPS"); do
    for limit in $N4_LIMITS; do
      log "${N4_TAG:-N4} 줄서기 ${N4_REG_RATE}/s 상한 $limit (${rep}번째)"
      settle; nginx_limit "$limit" $NO_LIMIT; reset_queue; open_event
      warmup -e SCENARIO=register -e RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 4000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
      reset_queue; open_event
      k6run "${N4_TAG:-N4}_reg_${limit}_$rep" -e SCENARIO=register -e RATES=$N4_REG_RATE -e STEP_SECONDS=$STEP
    done
  done
  echo "### ${N4_TAG:-N4} 줄서기 ${N4_REG_RATE}/s, 상한 후보별 ${N4_REPS}번 (단계 ${STEP}초, 단계마다 예열, 순번 조회 풀은 끔)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "${N4_TAG:-N4}_reg_{limit}_{rep}" reg "$N4_REG_RATE" "${N4_LIMITS// /,}" "$N4_REPS" "$STEP" | tee -a "$OUT/results.md"
  echo >> "$OUT/results.md"
}

stage_N6() { N4_TAG=N6; N4_LIMITS="$N6_LIMITS"; stage_N4; }

stage_N3() {
  local limit rep
  nginx_limit $NO_LIMIT; reset_queue; open_event
  fill_to $N_SMALL; dump_tokens $N_SMALL "$ABS_OUT/tokens-n3.txt"
  for rep in $(seq 1 "$N2_REPS"); do
    for limit in $N3_LIMITS; do
      log "N3 순번 조회 ${N2_POLL_RATE}/s 상한 $limit (${rep}번째)"
      settle; nginx_limit $NO_LIMIT "$limit"
      warmup -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-n3.txt" -e POLL_RATES=$([ "${QUICK:-0}" = "1" ] && echo 500 || echo 3000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
      k6run "N3_poll_${limit}_$rep" -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-n3.txt" -e POLL_RATES=$N2_POLL_RATE -e STEP_SECONDS=$STEP
    done
  done
  echo "### N3 순번 조회 경계 부하 ${N2_POLL_RATE}/s, 낮은 상한 후보별 ${N2_REPS}번 (대기자 ${N_SMALL}명 토큰, 단계 ${STEP}초, 단계마다 예열)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "N3_poll_{limit}_{rep}" poll "$N2_POLL_RATE" "${N3_LIMITS// /,}" "$N2_REPS" "$STEP" | tee -a "$OUT/results.md"
  echo >> "$OUT/results.md"
}

stage_D1() {
  local combo budget variant pool alive label
  psql_ -c "SELECT 1" > /dev/null
  echo -e "\n### D1 대기열 승격 속도별 홀드와 결제 (사용자 ${D1_USERS}명, 홀드 뒤 결제 전 ${D1_THINK}초, 앱 1대, 결제는 가짜 PG가 즉시 승인)" >> "$OUT/results.md"
  for combo in $D1_CASES; do
    budget=${combo%%:*}; variant=${combo##*:}
    pool=""; label="분산"
    if [ "$variant" = hot ]; then pool=$D1_HOT_POOL; label="집중(좌석 ${pool}개)"; fi
    if [ "$variant" = tiny ]; then pool=$D1_TINY_POOL; label="극단 집중(좌석 ${pool}개)"; fi
    log "D1 승격 ${budget}/s, 좌석 $label"
    export QUEUE_PROMOTION_BUDGET=$budget
    up 1s || return 1
    nginx_limit $NO_LIMIT
    reset_queue; seed; open_event
    psql_ -c "SELECT id FROM seat WHERE event_id=$EVENT_ID ORDER BY id" > "$ABS_OUT/seats-${budget}_${variant}.txt"
    K6_SCRIPT=k6/journey.js warmup -e USERS=$D1_WARM_USERS -e SEATS_FILE="$ABS_OUT/seats-${budget}_${variant}.txt" -e THINK=1 -e PHONE_BASE=90000000 -e WAIT_LIMIT=60
    DB_STATS=1 K6_SCRIPT=k6/journey.js k6run "D1_${budget}_${variant}" -e USERS=$D1_USERS -e SEATS_FILE="$ABS_OUT/seats-${budget}_${variant}.txt" \
      -e SEAT_POOL=$pool -e THINK=$D1_THINK -e PHONE_BASE=0 -e WAIT_LIMIT=$D1_WAIT
    [ -f "$OUT/D1_${budget}_${variant}.crashed" ] && alive=no || alive=yes
    python3 k6/summarize-db.py header | tee -a "$OUT/results.md"
    python3 k6/summarize-db.py row "승격 ${budget}/s, 좌석 $label" "$OUT/D1_${budget}_${variant}.summary.json" "$OUT/D1_${budget}_${variant}.stats.tsv" "$OUT/D1_${budget}_${variant}.db.tsv" $alive | tee -a "$OUT/results.md"
    python3 k6/summarize-queue.py snapdiff "$OUT/D1_${budget}_${variant}.before.json" "$OUT/D1_${budget}_${variant}.after.json" | tee -a "$OUT/results.md"
    echo "- 예약 상태별: $(psql_ -c "SELECT string_agg(status || ' ' || n, ', ') FROM (SELECT status, count(*) n FROM reservation WHERE event_id=$EVENT_ID GROUP BY status) t")" | tee -a "$OUT/results.md"
    echo | tee -a "$OUT/results.md" > /dev/null
  done
  export QUEUE_PROMOTION_BUDGET=100
}

# 활성 토큰으로 홀드와 결제를 정해진 속도로 보낸다(k6/hold.js). 케이스는 "rate:초당 건수:초:앱 코어" 또는 "burst:사용자 수:접속 퍼뜨림 초:앱 코어"
# D1은 순번 조회 간격(15초) 때문에 홀드가 승격 속도가 아니라 줄서기 도착 속도로 몰려서, 홀드 도착 속도를 직접 정하려고 대기열을 거치지 않고 토큰을 활성으로 만든다
stage_D2() {
  local combo mode a b cores n label tag alive tokens seats
  psql_ -c "SELECT 1" > /dev/null
  echo -e "\n### D2 홀드 도착 속도별 DB (활성 토큰으로 홀드와 결제를 직접 보냄, 홀드 뒤 결제 전 ${D1_THINK}초, 좌석 분산, 앱 1대, 결제는 가짜 PG가 즉시 승인)" >> "$OUT/results.md"
  for combo in $D2_CASES; do
    IFS=: read -r mode a b cores <<< "$combo"
    if [ "$mode" = rate ]; then n=$(( a * b )); label="초당 ${a}건 x ${b}초(${n}명), 앱 ${cores}코어"; else n=$a; label="${a}명이 ${b}초 안에 한꺼번에(대기열 없음), 앱 ${cores}코어"; fi
    tag="D2_${mode}_${a}_${cores}"
    log "D2 $label"
    export APP_CPUS=$cores
    up 1h || return 1
    nginx_limit $NO_LIMIT
    reset_queue; seed; open_event
    seats="$ABS_OUT/seats-$tag.txt"; tokens="$ABS_OUT/tokens-$tag.txt"
    psql_ -c "SELECT id FROM seat WHERE event_id=$EVENT_ID ORDER BY id" > "$seats"
    fill_to $(( n + D1_WARM_USERS ))
    rcli ZRANGE "queue:$EVENT_ID:waiting" 0 $(( D1_WARM_USERS - 1 )) > "$ABS_OUT/warm-$tag.txt"
    rcli ZRANGE "queue:$EVENT_ID:waiting" "$D1_WARM_USERS" $(( n + D1_WARM_USERS - 1 )) > "$tokens"
    cat "$ABS_OUT/warm-$tag.txt" "$tokens" | awk -v e="$EVENT_ID" '{print "SET queue:" e ":active:" $1 " 1 EX 3600"}' | rcli > /dev/null
    log "활성 토큰 $(rcli --scan --pattern "queue:$EVENT_ID:active:*" | wc -l | tr -d ' ')개"
    K6_SCRIPT=k6/hold.js warmup -e MODE=rate -e RATE=30 -e DURATION=5 -e TOKENS_FILE="$ABS_OUT/warm-$tag.txt" -e SEATS_FILE="$seats" -e THINK=1 -e PHONE_BASE=90000000
    if [ "$mode" = rate ]; then
      DB_STATS=1 K6_SCRIPT=k6/hold.js k6run "$tag" -e MODE=rate -e RATE=$a -e DURATION=$b -e TOKENS_FILE="$tokens" -e SEATS_FILE="$seats" -e THINK=$D1_THINK -e PHONE_BASE=0
    else
      DB_STATS=1 K6_SCRIPT=k6/hold.js k6run "$tag" -e MODE=burst -e USERS=$a -e SPREAD=$b -e TOKENS_FILE="$tokens" -e SEATS_FILE="$seats" -e THINK=$D1_THINK -e PHONE_BASE=0
    fi
    [ -f "$OUT/$tag.crashed" ] && alive=no || alive=yes
    python3 k6/summarize-db.py header | tee -a "$OUT/results.md"
    python3 k6/summarize-db.py row "$label" "$OUT/$tag.summary.json" "$OUT/$tag.stats.tsv" "$OUT/$tag.db.tsv" $alive | tee -a "$OUT/results.md"
    python3 k6/summarize-queue.py snapdiff "$OUT/$tag.before.json" "$OUT/$tag.after.json" | tee -a "$OUT/results.md"
    echo "- $label 홀드 성공이 DB에 생긴 속도(초 단위 집계): $(psql_ -c "SELECT 'peak ' || max(c) || '건/s, 평균 ' || round(avg(c)) || '건/s (' || count(*) || '초)' FROM (SELECT date_trunc('second', created_at) s, count(*) c FROM reservation WHERE event_id=$EVENT_ID GROUP BY 1) t")" | tee -a "$OUT/results.md"
    echo "- 예약 상태별: $(psql_ -c "SELECT string_agg(status || ' ' || n, ', ') FROM (SELECT status, count(*) n FROM reservation WHERE event_id=$EVENT_ID GROUP BY status) t")" | tee -a "$OUT/results.md"
    echo | tee -a "$OUT/results.md" > /dev/null
  done
  export APP_CPUS=2
}

# 저장소에 올라간 nginx.conf를 숫자 변경 없이 그대로 쓴다(출고 설정이 실제로 도는지 보는 단계용)
nginx_shipped() {
  export NGINX_CONF="$(pwd)/docker/nginx/nginx.conf"
  restart_nginx || { log "nginx를 띄우지 못해 측정을 멈춤"; exit 1; }
  log "nginx 상한: 저장소 설정 그대로(줄서기 $(grep -oE 'limit_conn queue_register [0-9]+' docker/nginx/nginx.conf | awk '{print $3}'), 순번 조회 $(grep -oE 'limit_conn queue_poll [0-9]+' docker/nginx/nginx.conf | awk '{print $3}'))"
}

cold_restart() { # 앱을 껐다 켜서 JIT가 식은 상태로 바로 부하를 받게 한다
  $COMPOSE restart app >> "$OUT/compose.log" 2>&1
  for _ in $(seq 1 120); do
    curl -s -m 2 "$ACTUATOR/actuator/health/readiness" | grep -q '"UP"' && break
    sleep 1
  done
  restart_nginx
}

exists_count() { # 토큰 파일 -> 토큰 키가 아직 있는 개수
  awk -v e="$EVENT_ID" '{print "EXISTS queue:" e ":token:" $1}' "$1" | rcli | grep -c '^1'
}

status_counts() { # 토큰 파일 -> 순번 조회 응답 코드 분포(nginx 경유, 한 번씩)
  xargs -P 20 -I{} curl -s -o /dev/null -m 5 -w '%{http_code}\n' -H "X-Queue-Token: {}" "$BASE/api/events/$EVENT_ID/queues/me" < "$1" | sort | uniq -c | awk '{printf "%s x %s ", $2, $1}'
}

poll_all() { # 토큰 파일
  xargs -P 20 -I{} curl -s -o /dev/null -m 5 -H "X-Queue-Token: {}" "$BASE/api/events/$EVENT_ID/queues/me" < "$1"
}

# E1 대기 토큰 갱신이 실제 앱에서 도는지. token-ttl을 짧게 두고 A는 계속 조회, B는 조회 안 함, C는 중간에 조회를 멈춘다
stage_E1() {
  local round a b c
  export QUEUE_TOKEN_TTL=${E1_TTL}s
  export QUEUE_POLL_FAR_MAX=$(( E1_TTL / 2 ))s
  up 1h || return 1
  nginx_shipped
  reset_queue; seed; open_event
  fill_to $(( E1_COUNT * 3 ))
  a="$ABS_OUT/e1-a.txt"; b="$ABS_OUT/e1-b.txt"; c="$ABS_OUT/e1-c.txt"
  rcli ZRANGE "queue:$EVENT_ID:waiting" 0 $(( E1_COUNT - 1 )) > "$a"
  rcli ZRANGE "queue:$EVENT_ID:waiting" "$E1_COUNT" $(( E1_COUNT * 2 - 1 )) > "$b"
  rcli ZRANGE "queue:$EVENT_ID:waiting" $(( E1_COUNT * 2 )) $(( E1_COUNT * 3 - 1 )) > "$c"
  log "E1 token-ttl ${E1_TTL}초, 조회 간격 ${E1_POLL_S}초, ${E1_ROUNDS}번 조회"
  for round in $(seq 1 "$E1_ROUNDS"); do
    poll_all "$a"
    [ "$round" -le $(( E1_ROUNDS / 2 )) ] && poll_all "$c"
    sleep "$E1_POLL_S"
  done
  echo -e "\n### E1 대기 토큰 갱신 (token-ttl ${E1_TTL}초, 조회 간격 ${E1_POLL_S}초로 ${E1_ROUNDS}번, 사용자 그룹마다 ${E1_COUNT}명, 승격 끔)" | tee -a "$OUT/results.md"
  echo "| 그룹 | 동작 | 끝난 뒤 토큰 키가 남은 수 | 끝난 뒤 순번 조회 응답 |" | tee -a "$OUT/results.md"
  echo "|---|---|---|---|" | tee -a "$OUT/results.md"
  echo "| A | 끝까지 ${E1_POLL_S}초마다 조회 | $(exists_count "$a") / $E1_COUNT | $(status_counts "$a") |" | tee -a "$OUT/results.md"
  echo "| B | 한 번도 조회 안 함 | $(exists_count "$b") / $E1_COUNT | $(status_counts "$b") |" | tee -a "$OUT/results.md"
  echo "| C | 절반 동안 조회하다 멈춤 | $(exists_count "$c") / $E1_COUNT | $(status_counts "$c") |" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
  export QUEUE_TOKEN_TTL=2h QUEUE_POLL_FAR_MAX=5m
  up 1h || return 1
}

# N3R 순번 조회 낮은 상한(250)을 N3과 같은 방식으로 다시 잰다. 토큰 갱신(조회마다 EXPIRE)이 처리량을 깎는지 보려는 것
stage_N3R() {
  N3_LIMITS="250"
  echo -e "\n### N3R 토큰 갱신을 넣은 뒤(조회마다 대기 토큰 수명을 늘림) 같은 방식으로 재측정. 비교 대상은 N3의 상한 250(받아준 8,974 ~ 9,444/s, p95 30.6 ~ 45.5ms, 6번 모두 생존)" >> "$OUT/results.md"
  stage_N3
}

# C2 출고 설정(저장소 nginx.conf 그대로)에서, 방금 재시작한 차가운 앱이 과부하를 바로 받아도 사는지
stage_C2() {
  local rep
  nginx_shipped
  for rep in $(seq 1 "$C2_REPS"); do
    log "C2 줄서기 ${N4_REG_RATE}/s, 재시작 직후 (${rep}번째)"
    settle; reset_queue; open_event; cold_restart
    k6run "C2_reg_500_$rep" -e SCENARIO=register -e RATES=$N4_REG_RATE -e STEP_SECONDS=$STEP
  done
  reset_queue; open_event
  fill_to $N_SMALL; dump_tokens $N_SMALL "$ABS_OUT/tokens-c2.txt"
  for rep in $(seq 1 "$C2_REPS"); do
    log "C2 순번 조회 ${N2_POLL_RATE}/s, 재시작 직후 (${rep}번째)"
    settle; cold_restart
    k6run "C2_poll_250_$rep" -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-c2.txt" -e POLL_RATES=$N2_POLL_RATE -e STEP_SECONDS=$STEP
  done
  echo -e "\n### C2 출고 설정(줄서기 500, 순번 조회 250)에서 재시작 직후의 차가운 앱이 바로 받는 과부하 (단계 ${STEP}초, 예열 없음)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "C2_reg_{limit}_{rep}" reg "$N4_REG_RATE" 500 "$C2_REPS" "$STEP" | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "C2_poll_{limit}_{rep}" poll "$N2_POLL_RATE" 250 "$C2_REPS" "$STEP" | tee -a "$OUT/results.md"
  echo >> "$OUT/results.md"
}

# P1 50만 명이 20초 안에 몰리는 모델(0~3초 15만, 3~10초 15만, 10~20초 20만)을 줄서기 도착률로 그대로 보낸다. 재시도는 없는 한 번의 흐름이다
stage_P1() {
  nginx_shipped
  reset_queue; open_event
  warmup -e SCENARIO=register -e RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 4000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
  reset_queue; open_event
  k6run P1 -e SCENARIO=peak -e PEAK_SCALE=$P1_SCALE
  echo -e "\n### P1 50만 명 20초 피크(0~3초 초당 50,000건, 3~10초 21,429건, 10~20초 20,000건, 규모 ${P1_SCALE}배, 출고 설정, 재시도 없음)" | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py totals "$OUT/P1.summary.json" | tee -a "$OUT/results.md"
  [ -f "$OUT/P1.crashed" ] && echo "- **앱 생존: 죽음**" | tee -a "$OUT/results.md" || echo "- 앱 생존: 죽지 않음" | tee -a "$OUT/results.md"
  echo "- 줄 선 사람(ZCARD): $(rcli ZCARD "queue:$EVENT_ID:waiting"), Redis used_memory $(( $(rcli INFO memory | awk -F: '/^used_memory:/{print $2}') / 1048576 ))MiB" | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py snapdiff "$OUT/P1.before.json" "$OUT/P1.after.json" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

# M1 순번 조회가 앱 CPU를 쓰는 중에 홀드와 결제가 들어오면 어떻게 되나(승격 속도를 올려도 되는지). 케이스는 "조회 초당:홀드 초당"
stage_M1() {
  local combo pr hr tag n polls warm holds alive poll_pid
  echo -e "\n### M1 순번 조회와 홀드, 결제가 같은 앱에서 동시에 (조회는 출고 상한 250, 홀드는 ${M1_HOLD_SECONDS}초 동안 정해진 속도, 앱 2코어, 가짜 PG)" >> "$OUT/results.md"
  for combo in $M1_CASES; do
    pr=${combo%%:*}; hr=${combo##*:}
    n=$(( hr * M1_HOLD_SECONDS )); tag="M1_${pr}_${hr}"
    log "M1 순번 조회 ${pr}/s + 홀드 ${hr}/s"
    export APP_CPUS=2
    up 1h || return 1
    nginx_shipped
    cold_restart
    reset_queue; seed; open_event
    fill_to $(( M1_POLL_TOKENS + D1_WARM_USERS + n ))
    polls="$ABS_OUT/m1-polls-$tag.txt"; warm="$ABS_OUT/m1-warm-$tag.txt"; holds="$ABS_OUT/m1-holds-$tag.txt"
    rcli ZRANGE "queue:$EVENT_ID:waiting" 0 $(( M1_POLL_TOKENS - 1 )) > "$polls"
    rcli ZRANGE "queue:$EVENT_ID:waiting" "$M1_POLL_TOKENS" $(( M1_POLL_TOKENS + D1_WARM_USERS - 1 )) > "$warm"
    rcli ZRANGE "queue:$EVENT_ID:waiting" $(( M1_POLL_TOKENS + D1_WARM_USERS )) $(( M1_POLL_TOKENS + D1_WARM_USERS + n - 1 )) > "$holds"
    cat "$warm" "$holds" | awk -v e="$EVENT_ID" '{print "SET queue:" e ":active:" $1 " 1 EX 3600"}' | rcli > /dev/null
    psql_ -c "SELECT id FROM seat WHERE event_id=$EVENT_ID ORDER BY id" > "$ABS_OUT/seats-$tag.txt"
    K6_SCRIPT=k6/hold.js warmup -e MODE=rate -e RATE=30 -e DURATION=5 -e TOKENS_FILE="$warm" -e SEATS_FILE="$ABS_OUT/seats-$tag.txt" -e THINK=1 -e PHONE_BASE=90000000
    warmup -e SCENARIO=poll -e TOKENS_FILE="$polls" -e POLL_RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 3000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
    if [ "$pr" -gt 0 ]; then
      k6 run --quiet --summary-export="$OUT/$tag.poll.summary.json" -e BASE=$BASE -e EVENT_ID="$EVENT_ID" -e SCENARIO=poll -e TOKENS_FILE="$polls" \
        -e POLL_RATES=$pr -e STEP_SECONDS=$(( M1_HOLD_SECONDS + 20 )) k6/queue.js > "$OUT/$tag.poll.k6.log" 2>&1 &
      poll_pid=$!
      sleep 8
    fi
    DB_STATS=1 K6_SCRIPT=k6/hold.js k6run "$tag" -e MODE=rate -e RATE=$hr -e DURATION=$M1_HOLD_SECONDS -e TOKENS_FILE="$holds" -e SEATS_FILE="$ABS_OUT/seats-$tag.txt" -e THINK=$D1_THINK -e PHONE_BASE=0
    [ "$pr" -gt 0 ] && wait "$poll_pid"
    [ -f "$OUT/$tag.crashed" ] && alive=no || alive=yes
    python3 k6/summarize-db.py header | tee -a "$OUT/results.md"
    python3 k6/summarize-db.py row "조회 ${pr}/s + 홀드 ${hr}/s" "$OUT/$tag.summary.json" "$OUT/$tag.stats.tsv" "$OUT/$tag.db.tsv" $alive | tee -a "$OUT/results.md"
    if [ "$pr" -gt 0 ]; then
      echo "- 같은 시간의 순번 조회: $(python3 k6/summarize-queue.py totals "$OUT/$tag.poll.summary.json" | tail -1)" | tee -a "$OUT/results.md"
    fi
    echo | tee -a "$OUT/results.md" > /dev/null
  done
}

# A2 앱 2대(각 2코어) 뒤에서 상한을 nginx 합계로 걸 때의 값. 설정 파일 주석의 "앱이 2대면 대략 두 배"를 확인한다
two_apps_up() {
  export COMPOSE_PROFILES=two NGINX_UPSTREAM=./nginx-upstream-2.conf
  export QUEUE_PROMOTION_INTERVAL=1h
  $COMPOSE up -d --build app app2 >> "$OUT/compose.log" 2>&1
  for _ in $(seq 1 120); do
    if curl -s -m 2 "$ACTUATOR/actuator/health/readiness" | grep -q '"UP"' && curl -s -m 2 "http://127.0.0.1:18082/actuator/health/readiness" | grep -q '"UP"'; then restart_nginx; return 0; fi
    sleep 2
  done
  log "앱 2대가 뜨지 않음"; return 1
}

app2_state() { docker inspect -f '{{.State.Status}} 종료코드 {{.State.ExitCode}} OOM {{.State.OOMKilled}} 재시작 {{.RestartCount}}' "$($COMPOSE ps -aq app2)"; }

stage_A2() {
  local limit rep
  two_apps_up || return 1
  reset_queue; seed; open_event
  for rep in $(seq 1 "$A2_REPS"); do
    for limit in $A2_REG_LIMITS; do
      log "A2 앱 2대, 줄서기 ${A2_REG_RATE}/s 상한 $limit (${rep}번째)"
      settle; nginx_limit "$limit" $NO_LIMIT; reset_queue; open_event
      warmup -e SCENARIO=register -e RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 8000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
      reset_queue; open_event
      k6run "A2_reg_${limit}_$rep" -e SCENARIO=register -e RATES=$A2_REG_RATE -e STEP_SECONDS=$STEP
      echo "- A2 줄서기 상한 $limit ${rep}번째 뒤 app2 상태: $(app2_state)" | tee -a "$OUT/results.md"
    done
  done
  echo -e "\n### A2 앱 2대 줄서기 ${A2_REG_RATE}/s, 상한 후보별 ${A2_REPS}번 (nginx 합계 상한, 단계마다 예열, 앱 각 2코어)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "A2_reg_{limit}_{rep}" reg "$A2_REG_RATE" "${A2_REG_LIMITS// /,}" "$A2_REPS" "$STEP" | tee -a "$OUT/results.md"
  nginx_limit $NO_LIMIT; reset_queue; open_event
  fill_to $N_SMALL; dump_tokens $N_SMALL "$ABS_OUT/tokens-a2.txt"
  for rep in $(seq 1 "$A2_REPS"); do
    for limit in $A2_POLL_LIMITS; do
      log "A2 앱 2대, 순번 조회 ${A2_POLL_RATE}/s 상한 $limit (${rep}번째)"
      settle; nginx_limit $NO_LIMIT "$limit"
      warmup -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-a2.txt" -e POLL_RATES=$([ "${QUICK:-0}" = "1" ] && echo 500 || echo 6000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
      k6run "A2_poll_${limit}_$rep" -e SCENARIO=poll -e TOKENS_FILE="$ABS_OUT/tokens-a2.txt" -e POLL_RATES=$A2_POLL_RATE -e STEP_SECONDS=$STEP
      echo "- A2 순번 조회 상한 $limit ${rep}번째 뒤 app2 상태: $(app2_state)" | tee -a "$OUT/results.md"
    done
  done
  echo -e "\n### A2 앱 2대 순번 조회 ${A2_POLL_RATE}/s, 상한 후보별 ${A2_REPS}번 (대기자 ${N_SMALL}명 토큰, 단계마다 예열)" >> "$OUT/results.md"
  python3 k6/summarize-queue.py compare "$OUT" "A2_poll_{limit}_{rep}" poll "$A2_POLL_RATE" "${A2_POLL_LIMITS// /,}" "$A2_REPS" "$STEP" | tee -a "$OUT/results.md"
  echo >> "$OUT/results.md"
}

# 출고 nginx 설정에 줄서기와 순번 조회의 초당 속도 제한(limit_req)을 더한 복사본으로 바꾼다. 0이면 그 제한은 넣지 않는다.
# limit_conn은 같은 순간의 동시 처리 수만 막아서 앱 CPU를 얼마나 쓰는지는 못 막는다(M1). 속도로 막아 홀드와 결제의 몫을 남기는지 보려는 것
nginx_variant() { # 줄서기 초당 제한, 순번 조회 초당 제한
  local reg=$1 poll=$2
  python3 - "$reg" "$poll" docker/nginx/nginx.conf "$ABS_OUT/nginx-variant.conf" <<'PY'
import sys
reg, poll, src, dst = sys.argv[1:5]
s = open(src).read()
zones, locs = "", ""
for name, key, rate in (("queue_register_rate", "$register_key", int(reg)), ("queue_poll_rate", "$poll_key", int(poll))):
    if rate > 0:
        zones += f"    limit_req_zone {key} zone={name}:1m rate={rate}r/s;\n"
        locs += f"            limit_req zone={name} burst={max(rate // 5, 1)} nodelay;\n"
s = s.replace("    limit_conn_status 503;\n", zones + "    limit_req_status 503;\n    limit_req_log_level info;\n    limit_conn_status 503;\n", 1)
s = s.replace("            limit_conn queue_poll 250;\n", "            limit_conn queue_poll 250;\n" + locs, 1)
open(dst, "w").write(s)
PY
  export NGINX_CONF="$ABS_OUT/nginx-variant.conf"
  restart_nginx || { log "nginx를 띄우지 못해 측정을 멈춤"; exit 1; }
  log "nginx 변형: 줄서기 속도 제한 ${reg}/s, 순번 조회 속도 제한 ${poll}/s (0은 끔), limit_conn은 출고 값"
}

# 정해진 속도의 홀드와 결제 도중에 다른 부하(순번 조회 또는 줄서기)를 같이 보낸다. nginx는 부르는 쪽이 먼저 맞춰 둔다
mixed_case() { # 이름 부하종류(poll|register) 부하 초당 홀드 초당 라벨 줄서기제한 순번조회제한(0이면 제한 없음)
  local tag=$1 kind=$2 load=$3 hr=$4 label=$5 reg=${6:-0} poll=${7:-0} n polls warm holds alive load_pid=""
  n=$(( hr * M1_HOLD_SECONDS ))
  export APP_CPUS=2
  up 1h || return 1
  nginx_shipped
  cold_restart
  reset_queue; seed; open_event
  fill_to $(( M1_POLL_TOKENS + D1_WARM_USERS + n ))
  polls="$ABS_OUT/$tag-polls.txt"; warm="$ABS_OUT/$tag-warm.txt"; holds="$ABS_OUT/$tag-holds.txt"
  rcli ZRANGE "queue:$EVENT_ID:waiting" 0 $(( M1_POLL_TOKENS - 1 )) > "$polls"
  rcli ZRANGE "queue:$EVENT_ID:waiting" "$M1_POLL_TOKENS" $(( M1_POLL_TOKENS + D1_WARM_USERS - 1 )) > "$warm"
  rcli ZRANGE "queue:$EVENT_ID:waiting" $(( M1_POLL_TOKENS + D1_WARM_USERS )) $(( M1_POLL_TOKENS + D1_WARM_USERS + n - 1 )) > "$holds"
  cat "$warm" "$holds" | awk -v e="$EVENT_ID" '{print "SET queue:" e ":active:" $1 " 1 EX 3600"}' | rcli > /dev/null
  psql_ -c "SELECT id FROM seat WHERE event_id=$EVENT_ID ORDER BY id" > "$ABS_OUT/$tag-seats.txt"
  nginx_variant "$reg" "$poll"
  K6_SCRIPT=k6/hold.js warmup -e MODE=rate -e RATE=30 -e DURATION=5 -e TOKENS_FILE="$warm" -e SEATS_FILE="$ABS_OUT/$tag-seats.txt" -e THINK=1 -e PHONE_BASE=90000000
  if [ "$kind" = poll ]; then
    warmup -e SCENARIO=poll -e TOKENS_FILE="$polls" -e POLL_RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 3000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
    k6 run --quiet --summary-export="$OUT/$tag.load.summary.json" -e BASE=$BASE -e EVENT_ID="$EVENT_ID" -e SCENARIO=poll -e TOKENS_FILE="$polls" \
      -e POLL_RATES=$load -e STEP_SECONDS=$(( M1_HOLD_SECONDS + 20 )) k6/queue.js > "$OUT/$tag.load.k6.log" 2>&1 &
  else
    warmup -e SCENARIO=register -e RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 3000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
    k6 run --quiet --summary-export="$OUT/$tag.load.summary.json" -e BASE=$BASE -e EVENT_ID="$EVENT_ID" -e SCENARIO=register \
      -e RATES=$load -e STEP_SECONDS=$(( M1_HOLD_SECONDS + 20 )) k6/queue.js > "$OUT/$tag.load.k6.log" 2>&1 &
  fi
  load_pid=$!
  ( sleep 22; { echo "# $(date +%H:%M:%S) 호스트 CPU"; top -l 1 -n 0 | grep "CPU usage"; ps -Ao pcpu,comm -r | head -7; } > "$OUT/$tag.host.txt" 2>&1 ) &
  sleep 8
  DB_STATS=1 K6_SCRIPT=k6/hold.js k6run "$tag" -e MODE=rate -e RATE=$hr -e DURATION=$M1_HOLD_SECONDS -e TOKENS_FILE="$holds" -e SEATS_FILE="$ABS_OUT/$tag-seats.txt" -e THINK=$D1_THINK -e PHONE_BASE=0
  wait "$load_pid"
  [ -f "$OUT/$tag.crashed" ] && alive=no || alive=yes
  python3 k6/summarize-db.py header | tee -a "$OUT/results.md"
  python3 k6/summarize-db.py row "$label" "$OUT/$tag.summary.json" "$OUT/$tag.stats.tsv" "$OUT/$tag.db.tsv" $alive | tee -a "$OUT/results.md"
  echo "- 같은 시간의 ${kind} 부하(${load}/s 요청): $(python3 k6/summarize-queue.py totals "$OUT/$tag.load.summary.json" | tail -1)" | tee -a "$OUT/results.md"
  echo "- 부하가 한창일 때 호스트: $(grep "CPU usage" "$OUT/$tag.host.txt" | sed -E "s/CPU usage: //"), 상위 프로세스 $(grep -vE "^#|CPU usage|%CPU" "$OUT/$tag.host.txt" | head -4 | awk '{n = split($2, a, "/"); printf "%s(%s%%) ", a[n], $1}')" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

# M2 순번 조회나 줄서기가 앱 CPU를 다 쓰는 동안 홀드가 느려지는 것(M1)을 nginx 속도 제한으로 막을 수 있는지. 케이스는 "종류:부하 초당:줄서기 제한:순번 조회 제한"
stage_M2() {
  local combo kind load reg poll
  echo -e "\\n### M2 속도 제한(limit_req)으로 홀드 몫을 남기는지 (홀드 ${M2_HOLD_RATE}/s를 ${M1_HOLD_SECONDS}초, 앱 2코어, 가짜 PG, limit_conn은 출고 값). 비교 기준은 M1(제한 없음)의 조회 10,000/s + 홀드 100/s: 홀드 p95 1,676ms, 실패 10건, Hikari 대기 171" >> "$OUT/results.md"
  for combo in $M2_CASES; do
    IFS=: read -r kind load reg poll <<< "$combo"
    log "M2 ${kind} ${load}/s, 줄서기 제한 ${reg}, 순번 조회 제한 ${poll}"
    mixed_case "M2_${kind}_${load}_${reg}_${poll}" "$kind" "$load" "$M2_HOLD_RATE" "${kind} ${load}/s + 홀드 ${M2_HOLD_RATE}/s (줄서기 제한 ${reg}/s, 순번 조회 제한 ${poll}/s, 0은 제한 없음)" "$reg" "$poll"
  done
}

# M3 M2에서 속도 제한을 걸어도 홀드가 안 빨라졌다. 받아주는 요청 수와 거절당하는 요청 폭주를 따로 보려고, 받아주는 만큼만 보내는 경우와 거의 다 거절되게 하는 경우를 비교한다
stage_M3() {
  M2_CASES="$M3_CASES"
  echo -e "\n### M3 홀드 지연의 원인 가르기: 받아주는 만큼만 보낼 때(1,500, 3,000/s)와 10,000/s를 거의 다 거절시킬 때(제한 100/s)를 비교. 같은 홀드 100/s" >> "$OUT/results.md"
  stage_M2
}

# A3 앱 2대에서 A2 때 CPU가 한가한데 모든 요청이 실패한 실행의 원인을 가린다. 단계 사이에 TIME_WAIT가 빠지길 기다리고 nginx 오류 로그를 남긴다
stage_A3() {
  local rate rep
  two_apps_up || return 1
  nginx_shipped
  for rate in $A3_REG_RATES; do
    for rep in $(seq 1 "$A3_REPS"); do
      log "A3 앱 2대, 줄서기 ${rate}/s 출고 상한 (${rep}번째)"
      settle; reset_queue; open_event
      warmup -e SCENARIO=register -e RATES=$([ "${QUICK:-0}" = "1" ] && echo 300 || echo 8000) -e STEP_SECONDS=$([ "${QUICK:-0}" = "1" ] && echo 3 || echo 15)
      reset_queue; open_event
      k6run "A3_reg_${rate}_$rep" -e SCENARIO=register -e RATES=$rate -e STEP_SECONDS=$STEP
      echo "- A3 줄서기 ${rate}/s ${rep}번째: TIME_WAIT $(netstat -an -p tcp | grep -c TIME_WAIT)개, app2 $(app2_state), nginx 오류 로그 $($COMPOSE logs --no-color --since 3m nginx 2>/dev/null | grep -E '\[(error|warn|crit|alert)\]' | sed -E 's/^.*(\[(error|warn|crit|alert)\])/\1/; s/[0-9]+#[0-9]+: \*[0-9]+ //; s/client: [0-9.]+, .*//' | cut -c1-70 | sort | uniq -c | sort -rn | head -3 | tr '\n' ';')" | tee -a "$OUT/results.md"
    done
  done
  echo -e "\\n### A3 앱 2대 줄서기 상한 출고 값(500), 도착률별 ${A3_REPS}번 (단계 ${STEP}초, 단계마다 예열, 단계 사이에 TIME_WAIT 대기)" >> "$OUT/results.md"
  for rate in $A3_REG_RATES; do
    python3 k6/summarize-queue.py compare "$OUT" "A3_reg_${rate}_{rep}" reg "$rate" "${rate}" "$A3_REPS" "$STEP" 2>/dev/null | tee -a "$OUT/results.md" || true
  done
  echo >> "$OUT/results.md"
}

# Q1 대기자가 많을 때 서버가 실제로 알려주는 다음 조회 간격으로 초당 순번 조회 요청 수를 계산한다
stage_Q1() {
  local sample responses
  nginx_shipped
  reset_queue; seed; open_event
  fill_to $Q1_USERS
  sample="$ABS_OUT/q1-sample.txt"; responses="$ABS_OUT/q1-responses.jsonl"
  awk -v n="$Q1_USERS" -v s="$Q1_SAMPLES" -v e="$EVENT_ID" 'BEGIN { for (i = 0; i < s; i++) { p = int(i * n / s); print "ZRANGE queue:" e ":waiting " p " " p } }' | rcli > "$sample"
  # 병렬로 받으면 응답이 한 줄에 섞여 깨진다. 순서대로 받는다
  while read -r token; do
    curl -s -m 5 -w '\n' -H "X-Queue-Token: $token" "$BASE/api/events/$EVENT_ID/queues/me"
  done < "$sample" > "$responses"
  echo -e "\n### Q1 대기자 ${Q1_USERS}명이 서버가 알려준 간격으로 조회할 때 초당 요청 수 (순번을 고르게 ${Q1_SAMPLES}곳에서 뽑아 실제 응답으로 계산, 승격 끔)" | tee -a "$OUT/results.md"
  echo "- 유효한 응답 $(grep -c '"rank"' "$responses") / $Q1_SAMPLES" | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py intervals "$responses" "$Q1_USERS" "$OUT/q1.rate" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

# M4 Q1이 계산한 초당 요청 수로 순번 조회를 보내면서 홀드와 결제를 같이 보낸다(조회 간격 정책이 홀드에 주는 영향)
stage_M4() {
  local rate
  rate=$(cat "$OUT/q1.rate" 2>/dev/null || echo "$M4_FALLBACK_RATE")
  [ "$rate" -gt 40000 ] && rate=40000
  echo -e "\n### M4 순번 조회 ${rate}/s(Q1의 계산값)와 홀드 ${M2_HOLD_RATE}/s를 같이 ${M1_HOLD_SECONDS}초 (출고 상한, 앱 2코어, 가짜 PG)" >> "$OUT/results.md"
  mixed_case "M4_poll_${rate}" poll "$rate" "$M2_HOLD_RATE" "순번 조회 ${rate}/s + 홀드 ${M2_HOLD_RATE}/s" 0 0
}

# F2 상한이 낮아 거절이 생기는 전체 흐름(F1)을 여러 번 돌려 재시도 분산 전후를 비교한다
stage_F2() {
  local i
  for i in $(seq 1 "$F2_REPS"); do
    log "F2 ${i}번째"
    echo -e "\n#### F2 ${i}번째" >> "$OUT/results.md"
    stage_F1
  done
}

# 활성 토큰을 만들어 홀드와 결제를 정해진 속도로 보낸다. 이름 초당 초 좌석풀(빈 값이면 전체) 라벨
hold_case() {
  local tag=$1 rate=$2 secs=$3 pool=$4 label=$5 n alive tokens warm seats
  n=$(( rate * secs ))
  nginx_shipped
  cold_restart
  reset_queue; seed; open_event
  fill_to $(( n + D1_WARM_USERS ))
  warm="$ABS_OUT/$tag-warm.txt"; tokens="$ABS_OUT/$tag-tokens.txt"; seats="$ABS_OUT/$tag-seats.txt"
  rcli ZRANGE "queue:$EVENT_ID:waiting" 0 $(( D1_WARM_USERS - 1 )) > "$warm"
  rcli ZRANGE "queue:$EVENT_ID:waiting" "$D1_WARM_USERS" $(( n + D1_WARM_USERS - 1 )) > "$tokens"
  cat "$warm" "$tokens" | awk -v e="$EVENT_ID" '{print "SET queue:" e ":active:" $1 " 1 EX 3600"}' | rcli > /dev/null
  psql_ -c "SELECT id FROM seat WHERE event_id=$EVENT_ID ORDER BY id" > "$seats"
  K6_SCRIPT=k6/hold.js warmup -e MODE=rate -e RATE=30 -e DURATION=5 -e TOKENS_FILE="$warm" -e SEATS_FILE="$seats" -e THINK=1 -e PHONE_BASE=90000000
  DB_STATS=1 K6_SCRIPT=k6/hold.js k6run "$tag" -e MODE=rate -e RATE=$rate -e DURATION=$secs -e TOKENS_FILE="$tokens" -e SEATS_FILE="$seats" ${pool:+-e SEAT_POOL=$pool} -e THINK=$D1_THINK -e PHONE_BASE=0
  [ -f "$OUT/$tag.crashed" ] && alive=no || alive=yes
  python3 k6/summarize-db.py header | tee -a "$OUT/results.md"
  python3 k6/summarize-db.py row "$label" "$OUT/$tag.summary.json" "$OUT/$tag.stats.tsv" "$OUT/$tag.db.tsv" $alive | tee -a "$OUT/results.md"
  python3 k6/summarize-queue.py snapdiff "$OUT/$tag.before.json" "$OUT/$tag.after.json" | tee -a "$OUT/results.md"
  echo | tee -a "$OUT/results.md" > /dev/null
}

# D3 같은 좌석 몇 개에 충돌이 몰릴 때 홀드가 DB 연결을 얼마나 쓰는지(충돌은 Redis에서 거절돼 SQL 없이 끝나야 한다)
stage_D3() {
  local rep
  echo -e "\n### D3 좌석 ${D3_POOL}개에 초당 ${D3_RATE}건을 ${D3_SECONDS}초 (거의 다 충돌, 앱 2코어, 가짜 PG)" >> "$OUT/results.md"
  for rep in $(seq 1 "$D3_REPS"); do
    export APP_CPUS=2
    up 1h || return 1
    log "D3 좌석 ${D3_POOL}개, 초당 ${D3_RATE}건 (${rep}번째)"
    hold_case "D3_$rep" "$D3_RATE" "$D3_SECONDS" "$D3_POOL" "충돌 폭주 ${rep}번째"
  done
}

# PG 가짜 PG의 응답 지연이 길어도 결제가 DB 연결을 붙잡고 있지 않은지
stage_PG() {
  local delay
  echo -e "\n### PG 가짜 PG 응답 지연별 홀드와 결제 (초당 ${PG_RATE}건을 ${PG_SECONDS}초, 앱 2코어)" >> "$OUT/results.md"
  for delay in $PG_DELAYS; do
    export APP_CPUS=2 PG_DELAY=${delay}ms
    up 1h || return 1
    log "PG 지연 ${delay}ms"
    hold_case "PG_${delay}" "$PG_RATE" "$PG_SECONDS" "" "PG 지연 ${delay}ms"
  done
  export PG_DELAY=0ms
  up 1h || return 1
}

stage_N3A() { stage_N3R; }
stage_N6A() { N6_LIMITS="500"; stage_N6; }

log "환경을 올림 (승격 꺼짐)"
up 1h || exit 1
nginx_limit "${QUEUE_LIMIT:-$NO_LIMIT}"
seed
env_info
for stage in $STAGES; do "stage_$stage"; done
log "끝. 결과는 $OUT/results.md"
