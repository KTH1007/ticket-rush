#!/bin/bash
# AOF 실험 2: 영속성 설정별 SET 처리량과 지연 (redis-benchmark, CPU 1코어, 연결 50개)
# 측정마다 새 컨테이너를 띄워 AOF 파일 크기와 자동 rewrite가 결과에 섞이지 않게 한다
# 지연은 묶음(-P)의 첫 응답까지의 시간이고 같은 묶음의 모든 명령에 같은 값이 기록된다
# 환경 변수: MODES, PIPELINES, REPS, REQS
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
MODES=${MODES:-"off aof-no aof-everysec aof-always"}
PIPELINES=${PIPELINES:-"1 16"}
REPS=${REPS:-3}; REQS=${REQS:-300000}
trap lab_cleanup_all EXIT

log "Redis $(docker run --rm "$LAB_REDIS_IMAGE" redis-server --version | grep -o 'v=[0-9.]*'), CPU 1코어, 요청 ${REQS}개, 연결 50개, SET만"
log "지연은 묶음을 보낸 뒤 첫 응답까지의 시간(같은 묶음의 모든 명령에 같은 값). 디스크는 Docker VM의 컨테이너 파일시스템"
echo "모드,파이프라인,회차,SET rps,SET p50 ms,SET p95 ms,SET p99 ms,aof_delayed_fsync"
for mode in $MODES; do
  for p in $PIPELINES; do
    for ((r = 1; r <= REPS; r++)); do
      set_mode "$mode"; aof_up tr-lab-aof "--cpus 1" "${ARGS[@]}" || exit 1
      out=$(docker run --rm --network $LAB_NET "$LAB_REDIS_IMAGE" redis-benchmark -h tr-lab-aof -q -n "$REQS" -c 50 -P "$p" -t set --csv 2>&1)
      row=$(echo "$out" | grep '"SET"' | tr -d '"')
      # 열: test, rps, avg, min, p50, p95, p99, max
      rps=$(echo "$row" | cut -d, -f2); p50=$(echo "$row" | cut -d, -f5); p95=$(echo "$row" | cut -d, -f6); p99=$(echo "$row" | cut -d, -f7)
      delayed=$(info_field tr-lab-aof persistence aof_delayed_fsync)
      echo "$mode,$p,$r,$rps,$p50,$p95,$p99,${delayed:--}"
      lab_rm tr-lab-aof
    done
  done
done
