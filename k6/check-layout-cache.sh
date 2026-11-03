#!/bin/bash
# nginx 좌석 배치 캐시가 의도대로 도는지 점검한다. 방금 만든(캐시가 빈) nginx에 대고 실행해야 한다
#   BASE(기본 http://127.0.0.1:18080), ACTUATOR(앱 지표, 기본 http://127.0.0.1:18081), EVENT_ID(기본 1)
# 확인: (1) 캐시가 빌 때 동시 요청이 앱에는 한 번만 감 (2) 이후는 HIT이고 앱의 Cache-Control, ETag, Vary가 유지됨
#       (3) gzip과 원본 응답이 따로 캐시됨 (4) If-None-Match는 nginx가 304로 답하고 앱에 가지 않음
BASE=${BASE:-http://127.0.0.1:18080}
ACTUATOR=${ACTUATOR:-http://127.0.0.1:18081}
URL="$BASE/api/events/${EVENT_ID:-1}/seats/layout"
FAILED=0

app_count() { # 앱이 받은 배치 요청(200) 누적 수
  curl -s -m 5 "$ACTUATOR/actuator/prometheus" | grep 'http_server_requests_seconds_count' | grep 'seats/layout' | grep 'status="200"' | awk '{s+=$NF} END{print s+0}'
}
header() { # 헤더 이름 [Accept-Encoding] -> 값
  curl -s -o /dev/null -D - -H "Accept-Encoding: ${2:-gzip}" "$URL" | grep -i "^$1:" | tr -d '\r' | cut -d' ' -f2-
}
verdict() { # 이름 조건 설명
  if [ "$2" = "1" ]; then echo "- PASS $1: $3"; else echo "- FAIL $1: $3"; FAILED=1; fi
}

before=$(app_count)
for _ in $(seq 1 50); do curl -s -o /dev/null -H 'Accept-Encoding: gzip' "$URL" & done; wait
after=$(app_count)
verdict "동시 요청 합치기" "$([ $((after - before)) -eq 1 ] && echo 1 || echo 0)" "캐시가 빈 상태의 동시 50개가 앱에 $((after - before))번 도착(기대 1)"
verdict "HIT과 헤더 유지" "$([ "$(header X-Cache-Status)" = HIT ] && [ -n "$(header ETag)" ] && header Cache-Control | grep -q 'max-age' && [ "$(header Vary)" = "Accept-Encoding" ] && echo 1 || echo 0)" "X-Cache-Status=$(header X-Cache-Status), Cache-Control=$(header Cache-Control)"
first=$(header X-Cache-Status identity); second=$(header X-Cache-Status identity)
verdict "압축 변형 분리" "$([ "$first" = MISS ] && [ "$second" = HIT ] && echo 1 || echo 0)" "원본(identity) 첫 요청 $first, 다음 요청 $second"
etag=$(header ETag); before=$(app_count)
code=$(curl -s -o /dev/null -H 'Accept-Encoding: gzip' -H "If-None-Match: $etag" -w '%{http_code}' "$URL")
after=$(app_count)
verdict "조건부 요청 304" "$([ "$code" = 304 ] && [ $((after - before)) -eq 0 ] && echo 1 || echo 0)" "If-None-Match 응답 $code, 앱 도착 $((after - before))번(기대 304와 0)"
exit $FAILED
