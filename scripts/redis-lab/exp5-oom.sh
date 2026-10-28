#!/bin/bash
# 실험 5: noeviction 상태에서 maxmemory에 닿았을 때 앱의 Lua 스크립트 동작
source "$(dirname "$0")/common.sh"
REPO_ROOT=${REPO_ROOT:-$LAB_DIR/../..}
SC="$REPO_ROOT/src/main/resources/scripts"
NAME=tr-lab-oom
trap lab_cleanup_all EXIT
lab_redis $NAME --maxmemory ${MAXMEM:-16mb} --maxmemory-policy noeviction --save "" --appendonly no
docker run --rm -i --network $LAB_NET \
  -e PROMOTE_LUA="$(cat "$SC/queue-promote.lua")" -e REGISTER_LUA="$(cat "$SC/queue-register.lua")" \
  -e STATUS_LUA="$(cat "$SC/queue-status.lua")" -e SEAT_CLAIM_LUA="$(cat "$SC/seat-claim.lua")" \
  python:3-alpine python -u - $NAME 6379 < "$LAB_DIR/oom_demo.py"
