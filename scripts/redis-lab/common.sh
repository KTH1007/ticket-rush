#!/bin/bash
# Redis 실험 공통. 모든 컨테이너는 tr-lab- 접두어와 전용 네트워크만 쓰고, 그 밖의 컨테이너는 거부한다
LAB_NET=tr-lab-net
LAB_REDIS_IMAGE=${LAB_REDIS_IMAGE:-redis:8-alpine}
LAB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

lab_guard() {
  case "$1" in
    tr-lab-*) ;;
    *) echo "거부: '$1'은 실험용 컨테이너가 아님" >&2; exit 1 ;;
  esac
}
lab_net_up() { docker network inspect $LAB_NET > /dev/null 2>&1 || docker network create $LAB_NET > /dev/null; }
lab_rm() { local c; for c in "$@"; do lab_guard "$c"; docker rm -fv "$c" > /dev/null 2>&1 || true; done; }  # -v: 익명 볼륨이 남지 않게
lab_cleanup_all() { # 실험이 만든 것만 지운다
  local c
  for c in $(docker ps -a --format '{{.Names}}' | grep '^tr-lab-' || true); do lab_rm "$c"; done
  docker network rm $LAB_NET > /dev/null 2>&1 || true
}
rcli() { local n=$1; shift; lab_guard "$n"; docker exec "$n" redis-cli "$@"; }
lab_redis() { # 이름 redis-server 인자들
  local n=$1 i; shift; lab_guard "$n"; lab_net_up; lab_rm "$n"
  docker run -d --name "$n" --network $LAB_NET "$LAB_REDIS_IMAGE" redis-server "$@" > /dev/null
  for i in $(seq 1 50); do [ "$(rcli "$n" PING 2> /dev/null)" = PONG ] && return 0; sleep 0.2; done
  echo "Redis가 뜨지 않음: $n" >&2; return 1
}
lab_py() { # 파이썬 스크립트 파일 인자들. 표준 입력으로 넘기고 같은 네트워크에서 실행한다
  local f=$1; shift
  docker run --rm -i --network $LAB_NET python:3-alpine python -u - "$@" < "$f"
}
log() { echo "[$(date +%H:%M:%S)] $*"; }

# ---- Sentinel 구성 (실험 2) ----
ip_of() { docker inspect -f "{{(index .NetworkSettings.Networks \"$LAB_NET\").IPAddress}}" "$1"; }
wait_for() { local i; for i in $(seq 1 "$1"); do eval "$2" > /dev/null 2>&1 && return 0; sleep 0.5; done; return 1; }
lab_sentinel_cluster() { # down-after-milliseconds, 이어서 세 Redis 노드에 줄 추가 인자들. 결과: MIP(마스터), R1, R2, SENTINELS 변수
  local down=$1 i; shift
  lab_cleanup_all; lab_net_up
  docker run -d --name tr-lab-s-master --network $LAB_NET "$LAB_REDIS_IMAGE" redis-server --save "" --appendonly no "$@" > /dev/null
  wait_for 40 "[ \"\$(rcli tr-lab-s-master PING)\" = PONG ]"
  MIP=$(ip_of tr-lab-s-master)
  for i in 1 2; do
    docker run -d --name tr-lab-s-r$i --network $LAB_NET "$LAB_REDIS_IMAGE" redis-server --save "" --appendonly no --replicaof "$MIP" 6379 "$@" > /dev/null
  done
  wait_for 80 "[ \"\$(rcli tr-lab-s-master INFO replication | grep -c '^slave[0-9]:.*state=online')\" = 2 ]" || { echo "레플리카가 붙지 않음" >&2; return 1; }
  for i in 1 2 3; do
    docker run -d --name tr-lab-s-sent$i --network $LAB_NET "$LAB_REDIS_IMAGE" sh -c "printf '%s\n' 'port 26379' 'sentinel monitor mymaster $MIP 6379 2' 'sentinel down-after-milliseconds mymaster $down' 'sentinel failover-timeout mymaster 15000' 'sentinel parallel-syncs mymaster 1' > /tmp/s.conf && exec redis-sentinel /tmp/s.conf" > /dev/null
  done
  R1=$(ip_of tr-lab-s-r1); R2=$(ip_of tr-lab-s-r2)
  SENTINELS="$(ip_of tr-lab-s-sent1),$(ip_of tr-lab-s-sent2),$(ip_of tr-lab-s-sent3)"
  for i in 1 2 3; do
    wait_for 120 "rcli tr-lab-s-sent$i -p 26379 SENTINEL master mymaster | paste -sd' ' - | grep -q 'num-slaves 2 .*num-other-sentinels 2'" || { echo "Sentinel $i이 구성을 다 알지 못함" >&2; return 1; }
  done
}
# 마스터의 네트워크 네임스페이스에서 지정한 주소들과의 양방향 통신을 막는다(마스터는 살아 있고 클라이언트와는 통신한다)
lab_partition() { # IP들
  local rules="" ip
  for ip in "$@"; do rules="$rules iptables -A INPUT -s $ip -j DROP; iptables -A OUTPUT -d $ip -j DROP;"; done
  docker run --rm --net container:tr-lab-s-master --cap-add NET_ADMIN tr-lab-nettools sh -ec "$rules date -u +%Y-%m-%dT%H:%M:%S.000Z"
}
lab_heal() { docker run --rm --net container:tr-lab-s-master --cap-add NET_ADMIN tr-lab-nettools sh -ec "iptables -F; date -u +%Y-%m-%dT%H:%M:%S.000Z"; }
