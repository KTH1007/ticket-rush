#!/bin/bash
# AOF 실험 공통. common.sh의 안전장치(tr-lab- 접두어, 전용 네트워크)를 그대로 쓴다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

# 모드별 redis-server 인자. 앱의 compose와 같은 maxmemory와 noeviction을 함께 준다
set_mode() {
  case $1 in
    off) ARGS=(--save "" --appendonly no) ;;
    rdb) ARGS=(--appendonly no) ;;  # 지금 compose처럼 --save를 주지 않아 기본 스냅샷 규칙이 켜진 상태
    aof-no) ARGS=(--save "" --appendonly yes --appendfsync no) ;;
    aof-everysec) ARGS=(--save "" --appendonly yes --appendfsync everysec) ;;
    aof-always) ARGS=(--save "" --appendonly yes --appendfsync always) ;;
    *) echo "모르는 모드: $1" >&2; return 1 ;;
  esac
  ARGS+=(--maxmemory 512mb --maxmemory-policy noeviction)
}

# 이름, 도커 옵션 문자열, redis-server 인자들
aof_up() {
  local n=$1 dopts=$2 i; shift 2
  lab_guard "$n"; lab_net_up; lab_rm "$n"
  docker run -d --name "$n" --network $LAB_NET $dopts "$LAB_REDIS_IMAGE" redis-server "$@" > /dev/null
  for i in $(seq 1 100); do [ "$(rcli "$n" PING 2> /dev/null)" = PONG ] && return 0; sleep 0.2; done
  echo "Redis가 뜨지 않음: $n" >&2; return 1
}

# 이름. 서버를 SIGKILL로 죽이고 같은 컨테이너를 다시 띄운 뒤 PONG까지 기다린다
aof_crash_restart() {
  local n=$1 i; lab_guard "$n"
  docker kill "$n" > /dev/null; docker start "$n" > /dev/null
  for i in $(seq 1 300); do [ "$(rcli "$n" PING 2> /dev/null)" = PONG ] && return 0; sleep 0.1; done
  echo "재시작 뒤 응답 없음: $n" >&2; return 1
}

# 이름. 기동 로그에서 마지막 "DB loaded" 줄
load_line() { docker logs "$1" 2>&1 | grep -E "DB loaded from" | tail -1 | sed 's/^.*\* //' | tr -d '"\r'; }
info_field() { rcli "$1" INFO "$2" | grep "^$3:" | cut -d: -f2 | tr -d '\r'; }
