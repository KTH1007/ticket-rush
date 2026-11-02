#!/bin/bash
# 앱 경유 관측: 하네스(k6/measure-queue.sh)의 단계를 돌리는 동안 측정 스택의 Redis를 1초 간격으로 읽는다
# 서버의 CPU 할당을 쓰지 않도록 다른 컨테이너에서 네트워크로 읽는다
# 사용: STAGES=S3 ./bottleneck-app.sh   (환경 변수: REPO_DIR, OUTDIR, QUEUE_MAX_WAITING)
LAB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "${REPO_DIR:-$(cd "$LAB_DIR/../.." && pwd)}" || exit 1
if [ -n "$(docker ps -aq --filter name=tr-measure-)" ]; then echo "거부: tr-measure- 컨테이너가 이미 있음" >&2; exit 1; fi
O=${OUTDIR:-/tmp/redis-lab-bottleneck-app}; mkdir -p "$O"
if [ -n "$(ls -A "$O" 2> /dev/null)" ]; then echo "거부: $O가 비어 있지 않음. 다른 OUTDIR을 지정" >&2; exit 1; fi
export QUEUE_MAX_WAITING=${QUEUE_MAX_WAITING:-600000}
START=$(mktemp)
STAGES=${STAGES:-S3} ./k6/measure-queue.sh > "$O/harness.out" 2>&1 &
HP=$!
ready=0
for _ in $(seq 1 300); do
  [ "$(docker exec tr-measure-redis-1 redis-cli PING 2> /dev/null)" = PONG ] && { ready=1; break; }
  kill -0 "$HP" 2> /dev/null || break
  sleep 2
done
if [ "$ready" != 1 ]; then
  kill "$HP" 2> /dev/null; wait "$HP" 2> /dev/null; rm -f "$START"
  echo "Redis가 준비되기 전에 하네스가 끝났거나 제한 시간이 지남. $O/harness.out 확인" | tee "$O/info.txt" >&2; exit 1
fi
NET=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' tr-measure-redis-1)
echo "네트워크=$NET, 하네스 PID=$HP" > "$O/info.txt"
docker rm -fv tr-lab-sampler > /dev/null 2>&1
docker run -d --name tr-lab-sampler --network "$NET" -v "$LAB_DIR/sampler.py:/s.py:ro" python:3-alpine python -u /s.py redis > /dev/null
wait $HP; echo "하네스 종료 코드=$?" >> "$O/info.txt"
docker stop -t 2 tr-lab-sampler > /dev/null 2>&1; docker logs tr-lab-sampler 2> /dev/null | grep '^{' > "$O/sampler.jsonl"; docker rm -fv tr-lab-sampler > /dev/null 2>&1
R=$(find k6/results -mindepth 1 -maxdepth 1 -type d -newer "$START" 2> /dev/null | sort | tail -1); rm -f "$START"
if [ -n "$R" ]; then cp -r "$R" "$O/harness-results"; echo "하네스 결과: $R" >> "$O/info.txt"; else echo "하네스 결과 없음(이번 실행이 만든 디렉터리가 없음)" >> "$O/info.txt"; fi
echo "관측 표본: $O/sampler.jsonl, 하네스 결과 사본: $O/harness-results (없으면 $O/info.txt 참고)"
