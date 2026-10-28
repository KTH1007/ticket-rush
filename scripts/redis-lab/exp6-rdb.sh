#!/bin/bash
# 실험 6: 앱의 Redis 구성(--save 미지정, appendonly no)에서 재시작 방식별로 무엇이 복원되는지
source "$(dirname "$0")/common.sh"
APP_FLAGS=(--maxmemory 512mb --maxmemory-policy noeviction --appendonly no)
trap lab_cleanup_all EXIT

state() { # 이름 라벨
  local c=$1
  log "$2: marker=$(rcli $c GET marker) seq=$(rcli $c GET queue:1:seq) dbsize=$(rcli $c DBSIZE)"
}
snapshot_wait() { # 이름: 기본 규칙(60초 안에 10000건 변경)으로 스냅샷이 저장될 때까지
  local c=$1 i changes
  for i in $(seq 1 150); do
    changes=$(rcli $c INFO persistence | grep '^rdb_changes_since_last_save' | cut -d: -f2 | tr -d '\r')
    [ "$changes" = 0 ] && return 0
    sleep 1
  done
  return 1
}
prepare() { # 이름 redis-server 인자들: 오래된 상태를 쓰고 스냅샷이 저장되길 기다린 뒤, 저장되지 않은 변경을 만든다
  local c=$1; shift
  lab_redis $c "$@"
  log "$c save 설정: $(rcli $c CONFIG GET save | paste -sd' ' -)"
  rcli $c SET marker old > /dev/null; rcli $c SET queue:1:seq 100 > /dev/null
  docker exec $c redis-benchmark -n 20000 -t set -q > /dev/null
  if snapshot_wait $c; then log "$c 스냅샷 저장됨 ($(rcli $c INFO persistence | grep -E '^rdb_last_bgsave_status' | tr -d '\r'))"; else log "$c 스냅샷이 저장되지 않음"; fi
  rcli $c SET marker new > /dev/null; rcli $c SET queue:1:seq 250 > /dev/null
  log "$c 저장되지 않은 변경 $(rcli $c INFO persistence | grep '^rdb_changes_since_last_save' | tr -d '\r')"
  state $c "종료 직전"
}

# 세 컨테이너를 동시에 준비한다(스냅샷 대기가 60초 이상이라)
prepare tr-lab-rdb-kill "${APP_FLAGS[@]}" &
prepare tr-lab-rdb-term "${APP_FLAGS[@]}" &
prepare tr-lab-rdb-off "${APP_FLAGS[@]}" --save "" &
wait

log "=== SIGKILL(docker kill) 뒤 같은 컨테이너를 다시 시작"
docker kill tr-lab-rdb-kill > /dev/null; docker start tr-lab-rdb-kill > /dev/null; sleep 1.5; state tr-lab-rdb-kill "SIGKILL 뒤"

log "=== SIGTERM(docker stop) 뒤 같은 컨테이너를 다시 시작"
docker stop tr-lab-rdb-term > /dev/null; docker start tr-lab-rdb-term > /dev/null; sleep 1.5; state tr-lab-rdb-term "SIGTERM 뒤"

log "=== --save \"\"(스냅샷 끔) 구성에 SIGKILL"
docker kill tr-lab-rdb-off > /dev/null; docker start tr-lab-rdb-off > /dev/null; sleep 1.5; state tr-lab-rdb-off "스냅샷 끔 + SIGKILL 뒤"

log "=== SIGKILL로 오래된 스냅샷만 남은 컨테이너를 삭제하고 같은 설정으로 새로 만들기(compose up 재생성에 해당)"
docker rm -fv tr-lab-rdb-kill > /dev/null
lab_redis tr-lab-rdb-kill "${APP_FLAGS[@]}"; state tr-lab-rdb-kill "재생성 뒤"
