#!/usr/bin/env bash
# postgres 메이저 버전 업그레이드 런북.
#
# 왜 pg_upgrade가 아니라 pg_dump/pg_restore인가: pg_upgrade는 신구 바이너리를
# 같은 환경에 동시에 둬야 해서 설정이 복잡하다. 이 프로젝트 규모(3만석 목표,
# README 기준)에서는 공식 문서가 권장하는 대로 dump/restore가 더 단순하고
# 충분히 빠르다. 데이터가 수십 GB를 넘기기 시작하면 그때 pg_upgrade로
# 바꾸는 걸 재검토한다.
#
# 실행 전 반드시 확인: 이 스크립트는 실제 운영 DB를 건드리지 않는다.
# --source-container/--target-container로 지정한 컨테이너를 대상으로만
# 동작한다. 운영에 쓰려면 아래 사용 예시를 참고해 실제 컨테이너/네트워크
# 이름으로 바꿔서 실행한다.
#
# 사용 예시(테스트, 2026-08-18에 postgres 16 -> 18로 실제 검증 완료):
#   docker network create pg-upgrade-test
#   docker run -d --name pg16-test --network pg-upgrade-test \
#     -e POSTGRES_DB=ticketrush -e POSTGRES_USER=ticketrush -e POSTGRES_PASSWORD=testpass \
#     postgres:16-alpine
#   docker run -d --name pg18-test --network pg-upgrade-test \
#     -e POSTGRES_DB=ticketrush -e POSTGRES_USER=ticketrush -e POSTGRES_PASSWORD=testpass \
#     postgres:18-alpine
#   ./scripts/postgres-major-upgrade.sh pg16-test pg18-test ticketrush ticketrush testpass
#
# 운영에 실제로 쓸 때는:
#   1. 새 메이저 버전 컨테이너를 먼저 띄운다(빈 DB 상태로).
#   2. 이 스크립트로 이관한다.
#   3. flyway migrate를 한 번 더 돌려 "up to date"로 나오는지 반드시 확인한다.
#   4. 문제없으면 docker-compose.yml의 이미지 태그를 교체하고 볼륨을 스왑한다.
#
# 절대 하지 말 것: 기존 볼륨을 삭제하고 새로 초기화하는 방식으로 메이저
# 업그레이드를 "우회"하지 않는다. 그건 데이터가 없을 때만 정당하다
# (postgres 16 -> 18 최초 전환 때 이 프로젝트가 실제로 그렇게 했다,
# 그때는 운영 데이터가 없었기 때문에 정당했다). 데이터가 있는 상태에서
# 볼륨을 지우면 그 데이터는 영구히 사라진다.

set -euo pipefail

SOURCE_CONTAINER="${1:?사용법: $0 <source-container> <target-container> <db-name> <db-user> <db-password>}"
TARGET_CONTAINER="${2:?}"
DB_NAME="${3:?}"
DB_USER="${4:?}"
DB_PASSWORD="${5:?}"

DUMP_FILE="/tmp/pg-upgrade-$(date +%Y%m%d%H%M%S).dump"

echo "[1/5] ${SOURCE_CONTAINER}에서 pg_dump로 백업 중..."
docker exec "${SOURCE_CONTAINER}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc -f /tmp/pg-upgrade.dump
docker cp "${SOURCE_CONTAINER}:/tmp/pg-upgrade.dump" "${DUMP_FILE}"
echo "    백업 완료: ${DUMP_FILE} ($(du -h "${DUMP_FILE}" | cut -f1))"

echo "[2/5] ${TARGET_CONTAINER}가 응답하는지 확인 중..."
docker exec "${TARGET_CONTAINER}" pg_isready -U "${DB_USER}"

echo "[3/5] ${TARGET_CONTAINER}로 백업 파일 복사 중..."
docker cp "${DUMP_FILE}" "${TARGET_CONTAINER}:/tmp/pg-upgrade.dump"

echo "[4/5] pg_restore 실행 중..."
docker exec "${TARGET_CONTAINER}" pg_restore -U "${DB_USER}" -d "${DB_NAME}" --no-owner --no-privileges /tmp/pg-upgrade.dump

echo "[5/5] 검증: row count 비교"
echo "  --- source (${SOURCE_CONTAINER}) ---"
docker exec "${SOURCE_CONTAINER}" psql -U "${DB_USER}" -d "${DB_NAME}" -c \
  "SELECT schemaname, relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname;"
echo "  --- target (${TARGET_CONTAINER}) ---"
docker exec "${TARGET_CONTAINER}" psql -U "${DB_USER}" -d "${DB_NAME}" -c \
  "SELECT schemaname, relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname;"

echo
echo "위 두 표의 n_live_tup이 테이블별로 일치하는지 눈으로 확인할 것."
echo "그다음 반드시: ./gradlew build 또는 flyway migrate로 스키마 인식이 정상인지 재확인할 것."
