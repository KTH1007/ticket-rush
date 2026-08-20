-- 개발/부하테스트용 이벤트-등급-좌석 시드 데이터 (이슈 #42).
--
-- 관리자 등록 API는 만들지 않기로 함(CRUD보다 동시성 제어에 리소스를 집중하기
-- 위한 스코프 판단). 대신 이 스크립트로 개발/부하테스트용 데이터를 채운다.
--
-- seat은 공연당 수만 건(README 목표 규모 30,000석)이라 row-by-row INSERT는
-- 느리다. generate_series로 좌석 좌표를 한 번에 만들고 set 연산으로 그대로
-- INSERT한다.
--
-- 실행 방법:
--   docker exec -i tr-postgres psql -U ticketrush -d ticketrush < scripts/seed-event.sql
--
-- 재실행 가능: event_title로 식별되는 기존 시드 데이터(seat -> grade -> event
-- 순서로 자식부터)만 지우고 다시 만든다. 다른 공연/예약 데이터는 건드리지
-- 않는다.
--
-- 규모를 바꾸려면 아래 sections_config VALUES의 row_count/seats_per_row만
-- 고치면 된다. 기본값은 VIP 2,000 + R 10,000 + S 18,000 = 30,000석.

\set ON_ERROR_STOP on
\set event_title 'ticket-rush 시드 공연'

BEGIN;

-- 기존 시드 데이터 정리. FK 방향이 seat -> grade -> event라 자식부터 지운다.
DELETE FROM seat
 WHERE event_id IN (SELECT id FROM event WHERE title = :'event_title');
DELETE FROM grade
 WHERE event_id IN (SELECT id FROM event WHERE title = :'event_title');
DELETE FROM event
 WHERE title = :'event_title';

WITH seed_event AS (
    INSERT INTO event (title, venue, description, opens_at, starts_at, created_at, updated_at)
    VALUES (
        :'event_title',
        '잠실종합운동장',
        '부하테스트/개발용 시드 데이터. scripts/seed-event.sql로 생성됨',
        now(),
        now() + interval '20 days',
        now(),
        now()
    )
    RETURNING id
),
seed_grades AS (
    INSERT INTO grade (event_id, name, price, created_at, updated_at)
    SELECT seed_event.id, g.name, g.price, now(), now()
    FROM seed_event
    CROSS JOIN (VALUES ('VIP', 200000), ('R', 120000), ('S', 80000)) AS g(name, price)
    RETURNING id, name
),
-- seq, section, grade_name, row_count, seats_per_row. 여기 숫자만 바꾸면 규모가
-- 바뀐다. seq는 ordinal 배정 순서용이다. section 텍스트의 알파벳 순서(우연히
-- R < S < VIP가 됨)에 기대지 않고, 이 목록에 적은 순서 그대로 ordinal 구간이
-- 나뉘게 하기 위함이다(디버깅/부하테스트 때 ordinal 범위만 보고 구역을
-- 유추할 수 있음).
sections_config (seq, section, grade_name, row_count, seats_per_row) AS (
    VALUES
        (1, 'VIP', 'VIP', 40, 50),
        (2, 'R',   'R',   100, 100),
        (3, 'S',   'S',   200, 90)
),
seat_positions AS (
    SELECT
        seed_event.id AS event_id,
        seed_grades.id AS grade_id,
        sections_config.seq,
        sections_config.section,
        row_num,
        seat_no
    FROM seed_event
    CROSS JOIN sections_config
    JOIN seed_grades ON seed_grades.name = sections_config.grade_name
    CROSS JOIN LATERAL generate_series(1, sections_config.row_count) AS r(row_num)
    CROSS JOIN LATERAL generate_series(1, sections_config.seats_per_row) AS n(seat_no)
)
INSERT INTO seat (event_id, grade_id, section, row_label, seat_no, ordinal, created_at, updated_at)
SELECT
    event_id,
    grade_id,
    section,
    row_num::text,
    seat_no::smallint,
    -- ordinal은 Redis 비트맵 인덱스라 0부터 연속이어야 한다(V1 마이그레이션
    -- 주석 참고). seq/row_num/seat_no 순서로 정렬해 부여하면 sections_config에
    -- 적은 순서(VIP -> R -> S) 그대로 ordinal 구간이 나뉘고, 재실행해도 항상
    -- 같은 좌표가 같은 ordinal을 받는다.
    (row_number() OVER (ORDER BY seq, row_num, seat_no) - 1) AS ordinal,
    now(),
    now()
FROM seat_positions;

COMMIT;

-- 검증: seat 개수와 ordinal 연속성.
-- min_ordinal=0, max_ordinal=seat_count-1이면 0부터 연속(gap 없음)이 보장된다.
-- uk_seat_ordinal 제약이 이미 중복은 막으므로 여기선 범위만 확인하면 된다.
SELECT
    count(*) AS seat_count,
    min(ordinal) AS min_ordinal,
    max(ordinal) AS max_ordinal
FROM seat
WHERE event_id = (SELECT id FROM event WHERE title = :'event_title');
