#!/bin/bash
# AOF 실험 4: 컨테이너를 다시 만들었을 때(볼륨 유무)와, RDB에서 AOF로 전환하는 절차
#  R1. AOF everysec, 볼륨 없음: restart는 보존, 컨테이너 삭제 후 재생성은 소실
#  R2. AOF everysec, 이름 있는 볼륨: 컨테이너 삭제 후 재생성에도 보존
#  R3. 전환 절차: RDB로 쓰던 인스턴스에서 CONFIG SET appendonly yes로 AOF를 만든 뒤 설정을 바꿔 재시작하면 보존되는가
source "$(dirname "${BASH_SOURCE[0]}")/aof-common.sh"
VOL=tr-lab-aofvol
cleanup() { lab_cleanup_all; docker volume rm $VOL > /dev/null 2>&1; }
trap cleanup EXIT; cleanup
get() { rcli tr-lab-aof GET "$1"; }
show() { echo "  $1: k1=[$(get k1)] k2=[$(get k2)] DBSIZE=$(rcli tr-lab-aof DBSIZE)"; }

log "R1. AOF everysec, 볼륨 없음"
set_mode aof-everysec; aof_up tr-lab-aof "--cpus 1" "${ARGS[@]}"
rcli tr-lab-aof SET k1 v1 > /dev/null; sleep 1.2
aof_crash_restart tr-lab-aof; show "SIGKILL 뒤 같은 컨테이너 재시작"
docker restart tr-lab-aof > /dev/null; wait_for 50 "[ \"\$(rcli tr-lab-aof PING)\" = PONG ]"; show "docker restart 뒤"
docker rm -fv tr-lab-aof > /dev/null; aof_up tr-lab-aof "--cpus 1" "${ARGS[@]}"; show "컨테이너 삭제 후 같은 설정으로 재생성"
lab_rm tr-lab-aof

log "R2. AOF everysec, 이름 있는 볼륨($VOL)"
docker volume create $VOL > /dev/null
aof_up tr-lab-aof "--cpus 1 -v $VOL:/data" "${ARGS[@]}"
rcli tr-lab-aof SET k1 v1 > /dev/null; sleep 1.2
docker rm -fv tr-lab-aof > /dev/null; aof_up tr-lab-aof "--cpus 1 -v $VOL:/data" "${ARGS[@]}"; show "컨테이너 삭제 후 같은 볼륨으로 재생성"
lab_rm tr-lab-aof; docker volume rm $VOL > /dev/null

log "R3. 전환 절차 (RDB로 쓰던 인스턴스 -> AOF)"
docker volume create $VOL > /dev/null
set_mode rdb; aof_up tr-lab-aof "--cpus 1 -v $VOL:/data" "${ARGS[@]}"
rcli tr-lab-aof SET k1 v1 > /dev/null; rcli tr-lab-aof SAVE > /dev/null
echo "  1) RDB로 쓰는 중: k1 저장됨, /data=$(docker exec tr-lab-aof ls /data | paste -sd' ' -)"
rcli tr-lab-aof CONFIG SET appendonly yes > /dev/null
wait_for 100 "[ \"\$(info_field tr-lab-aof persistence aof_enabled)\" = 1 ] && [ \"\$(info_field tr-lab-aof persistence aof_rewrite_in_progress)\" = 0 ]"
rcli tr-lab-aof SET k2 v2 > /dev/null; sleep 1.2
echo "  2) CONFIG SET appendonly yes 뒤: aof_enabled=$(info_field tr-lab-aof persistence aof_enabled), /data=$(docker exec tr-lab-aof ls /data /data/appendonlydir 2>/dev/null | paste -sd' ' -)"
docker stop tr-lab-aof > /dev/null; docker rm tr-lab-aof > /dev/null
set_mode aof-everysec; aof_up tr-lab-aof "--cpus 1 -v $VOL:/data" "${ARGS[@]}"; show "3) 새 설정(--appendonly yes --save \"\")으로 같은 볼륨 재시작"
echo "  기동 로그: $(load_line tr-lab-aof)"
lab_rm tr-lab-aof; docker volume rm $VOL > /dev/null
echo "== 정리 확인 =="; docker ps -a --format '{{.Names}}' | grep '^tr-lab' || echo "컨테이너 없음"; docker volume ls --format '{{.Name}}' | grep '^tr-lab' || echo "볼륨 없음"
