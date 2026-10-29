#!/bin/bash
# 실험 3 보충: ZSET 크기에 따라 DEL의 블로킹이 어떻게 늘어나는지. 크기마다 DEL 3번, UNLINK 3번
source "$(dirname "$0")/common.sh"
for n in ${SIZES:-250000 500000 2000000 4000000}; do
  log "=== N=$n"
  N=$n REPS=3 EXTRA_REPS=0 RESULT=/tmp/exp3-scale-$n.jsonl bash "$LAB_DIR/exp3-bigkey.sh" 2>&1 | grep -E "적재|\| default" | cut -c1-210
done
