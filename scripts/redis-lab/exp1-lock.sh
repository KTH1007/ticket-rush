#!/bin/bash
# 실험 1: Redis 락이 깨지는 재현(TTL, 남의 락 해제, watchdog, 정지, fencing)과 앱의 좌석 스크립트
source "$(dirname "$0")/common.sh"
REPO_ROOT=${REPO_ROOT:-$LAB_DIR/../..}
NAME=tr-lab-lock
trap lab_cleanup_all EXIT
lab_redis $NAME --save "" --appendonly no
docker run --rm -i --network $LAB_NET \
  -e SEAT_CLAIM_LUA="$(cat "$REPO_ROOT/src/main/resources/scripts/seat-claim.lua")" \
  -e SEAT_RELEASE_LUA="$(cat "$REPO_ROOT/src/main/resources/scripts/seat-release.lua")" \
  python:3-alpine python -u - $NAME 6379 < "$LAB_DIR/lock_demo.py"
