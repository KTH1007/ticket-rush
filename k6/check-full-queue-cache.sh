#!/bin/bash
# nginx가 가득 찬 대기열의 줄서기 거절(503 QUEUE_FULL)을 앱에 묻지 않고 재사용하는지 점검한다. 대기열이 이미 상한에 닿은 공연에 대고 실행해야 한다
#   BASE(기본 http://127.0.0.1:18080), ACTUATOR(앱 지표 주소, 앱이 여러 대면 공백으로 구분해 모두 적는다. 기본 http://127.0.0.1:18081), EVENT_ID(기본 1)
# 확인: (1) 거절이 MISS 다음 HIT이고 상태, code, Retry-After, Cache-Control이 그대로 (2) HIT 10번이 앱에 가지 않음
#       (3) 2초가 지나면 다시 앱에 물음(nginx가 만료를 초 단위 시계로 판정해 max-age=1은 실제로 1~2초 유지됨) (4) 줄서기가 아닌 경로에는 캐시 헤더가 없음
BASE=${BASE:-http://127.0.0.1:18080}
ACTUATORS=${ACTUATOR:-http://127.0.0.1:18081}
URL="$BASE/api/events/${EVENT_ID:-1}/queues"
FAILED=0

app_full() { # 모든 앱이 대기열 가득 참으로 거절한 등록 수의 합(누적). 지표를 못 읽거나 카운터가 없으면 실패
  local total=0 url n
  for url in $ACTUATORS; do
    n=$(curl -sf -m 5 "$url/actuator/prometheus" | awk '/^queue_register_rejected_total\{.*reason="full"/{s += $NF; found = 1} END{if (!found) exit 1; printf "%d", s}') || return 1
    total=$((total + n))
  done
  echo "$total"
}
metrics_failed() { # 읽지 못한 채로 0건으로 보고 통과하는 일이 없게 검사를 바로 끝낸다
  echo "- FAIL 지표 조회: $ACTUATORS 에서 queue_register_rejected_total{reason=\"full\"}를 읽지 못함"
  exit 1
}
probe() { # 줄서기 한 번의 응답(상태, 헤더, 본문)
  curl -s -i -m 5 -X POST "$URL" | tr -d '\r'
}
cache_status() { # 응답 -> X-Cache-Status 값
  echo "$1" | grep -i '^x-cache-status:' | cut -d' ' -f2-
}
verdict() { # 이름 조건 설명
  if [ "$2" = "1" ]; then echo "- PASS $1: $3"; else echo "- FAIL $1: $3"; FAILED=1; fi
}

sleep 2.2 # 앞선 요청이 남긴 사본이 만료되게 한다
before=$(app_full) || metrics_failed
first=$(probe); second=$(probe)
verdict "MISS 다음 HIT" "$([ "$(cache_status "$first")" = MISS ] && [ "$(cache_status "$second")" = HIT ] && echo 1 || echo 0)" \
  "첫 거절 $(cache_status "$first"), 다음 거절 $(cache_status "$second")"
kept=1
for response in "$first" "$second"; do
  echo "$response" | head -1 | grep -q ' 503' || kept=0
  echo "$response" | grep -q '"code":"QUEUE_FULL"' || kept=0
  echo "$response" | grep -qi '^retry-after: ' || kept=0
  echo "$response" | grep -qi '^cache-control: max-age=1' || kept=0
  echo "$response" | grep -q '"traceId"' && kept=0
done
verdict "응답 유지" "$kept" "두 응답 모두 503, code QUEUE_FULL, Retry-After, Cache-Control: max-age=1, 본문에 요청별 traceId 없음"

mid=$(app_full) || metrics_failed
hits=0
for _ in $(seq 1 10); do [ "$(cache_status "$(probe)")" = HIT ] && hits=$((hits + 1)); done
after=$(app_full) || metrics_failed
verdict "HIT은 앱에 가지 않음" "$([ "$hits" -eq 10 ] && [ "$after" -eq "$mid" ] && echo 1 || echo 0)" \
  "10번 중 HIT ${hits}번, 앱까지 닿은 거절 $((after - mid))건(기대 10과 0). 처음 두 요청은 $((mid - before))건"

sleep 2.2
later=$(cache_status "$(probe)")
verdict "2초 뒤 다시 앱에 물음" "$([ "$later" = MISS ] || [ "$later" = EXPIRED ] && echo 1 || echo 0)" "2.2초 뒤 첫 거절 ${later}(기대 MISS 또는 EXPIRED)"

other=$(curl -s -i -m 5 "$BASE/api/events" | grep -ci '^x-cache-status:')
verdict "다른 경로는 캐시 안 함" "$([ "$other" -eq 0 ] && echo 1 || echo 0)" "공연 목록 응답의 X-Cache-Status 헤더 ${other}개(기대 0)"
exit $FAILED
