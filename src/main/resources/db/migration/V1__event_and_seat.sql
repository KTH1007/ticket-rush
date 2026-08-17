-- =========================================
-- event
-- =========================================
CREATE TABLE event (
    id          bigserial PRIMARY KEY,
    title       varchar(200) NOT NULL,
    venue       varchar(200) NOT NULL,
    description text,
    opens_at    timestamptz NOT NULL,
    starts_at   timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

COMMENT ON TABLE  event IS '공연. 캐싱 대상인 정적 데이터';
COMMENT ON COLUMN event.opens_at IS
    '예매 오픈 시각. 판정은 서버 시각(NTP 동기화) 기준으로만 한다. 클라이언트 시각은 신뢰하지 않는다';
COMMENT ON COLUMN event.starts_at IS '공연 시작 시각. 예매 오픈과 다르다';

-- =========================================
-- grade
-- =========================================
CREATE TABLE grade (
    id         bigserial PRIMARY KEY,
    event_id   bigint NOT NULL REFERENCES event(id),
    name       varchar(50) NOT NULL,
    price      integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_grade_event_name UNIQUE (event_id, name),
    CONSTRAINT ck_grade_price CHECK (price >= 0)
);

COMMENT ON TABLE  grade IS '좌석 등급. 가격의 소유자';
COMMENT ON COLUMN grade.price IS
    '결제 금액 재검증의 기준값. 클라이언트가 보낸 금액은 신뢰하지 않고 이 값으로 재계산한다. 캐시가 아니라 DB에서 직접 조회한다';
COMMENT ON CONSTRAINT uk_grade_event_name ON grade IS
    '같은 공연에 같은 이름의 등급이 둘일 수 없다';

-- =========================================
-- seat
-- =========================================
CREATE TABLE seat (
    id              bigserial PRIMARY KEY,
    event_id        bigint NOT NULL REFERENCES event(id),
    grade_id        bigint NOT NULL REFERENCES grade(id),
    section         varchar(20) NOT NULL,
    row_label       varchar(10) NOT NULL,
    seat_no         smallint NOT NULL,
    ordinal         integer NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'AVAILABLE',
    reservation_id  bigint,     -- FK는 V2에서 추가한다 (reservation 테이블 생성 후)
    phone_hash      bytea,
    slot_no         smallint,
    hold_expires_at timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,

    CONSTRAINT uk_seat_position UNIQUE (event_id, section, row_label, seat_no),
    CONSTRAINT uk_seat_ordinal  UNIQUE (event_id, ordinal),
    CONSTRAINT ck_seat_status   CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    CONSTRAINT ck_seat_slot_no  CHECK (slot_no IS NULL OR slot_no IN (1, 2)),
    CONSTRAINT ck_seat_assignment CHECK (
        (status = 'AVAILABLE' AND reservation_id IS NULL)
        OR (status <> 'AVAILABLE' AND reservation_id IS NOT NULL)
    )
);

COMMENT ON TABLE seat IS
    '좌석. 배정 단위이자 동시성 제어의 최종 판정 대상. 공연당 수만 행이라 조회는 반드시 인덱스를 타야 한다';

COMMENT ON COLUMN seat.event_id IS
    'ID 간접참조. 공연 하나에 좌석이 수만 개라 객체 참조로 두면 좌석 하나를 건드릴 때 거대한 애그리거트가 딸려온다';
COMMENT ON COLUMN seat.grade_id IS
    '등급. 한 예약의 2석이 서로 다른 등급일 수 있어 금액은 좌석별로 합산한다';
COMMENT ON COLUMN seat.ordinal IS
    'Redis 비트맵의 비트 인덱스. 공연 등록 시 0부터 부여하고 이후 절대 변경하지 않는다. 바뀌면 비트맵 전체가 무의미해진다';
COMMENT ON COLUMN seat.status IS
    'AVAILABLE, HELD, SOLD. 조건부 UPDATE의 WHERE 대상이며 이것이 중복 배정의 최종 방어선이다';
COMMENT ON COLUMN seat.reservation_id IS
    '배정된 예약. V2에서 DEFERRABLE INITIALLY DEFERRED FK를 건다. 좌석 선점 UPDATE와 예약 INSERT의 순서가 트랜잭션 안에서 얽혀도 검증이 커밋 시점으로 미뤄지므로, 순서 제약 없이 참조 무결성을 유지한다';
COMMENT ON COLUMN seat.phone_hash IS
    'HMAC-SHA256(전화번호, 서버 키). 원문은 저장하지 않는다. 1인 2매 제한 인덱스의 대상이라 이 테이블에 비정규화했다';
COMMENT ON COLUMN seat.slot_no IS
    '1인 2매 제한용 슬롯(1 또는 2). 좌석 단위로 세므로 예약을 두 번에 나눠 사도 막힌다. AVAILABLE 좌석은 NULL이다';
COMMENT ON COLUMN seat.hold_expires_at IS
    '홀드 만료 시각. 만료 스케줄러가 인덱스로 훑는 대상. Redis TTL에 의존하지 않고 DB가 판정한다';
COMMENT ON COLUMN seat.version IS
    '낙관적 락. 조건부 UPDATE가 선점 경합을 막고, 이 컬럼은 HELD 이후의 상태 전이 경쟁(결제 확정 대 홀드 만료)을 감지한다';

COMMENT ON CONSTRAINT ck_seat_assignment ON seat IS
    '상태와 배정 컬럼의 정합성. 정상 경로에서는 걸리지 않고 배치나 수동 SQL 실수를 막는 최후 방어선';
COMMENT ON CONSTRAINT uk_seat_ordinal ON seat IS
    '비트맵 인덱스 중복 방지. 두 좌석이 같은 비트를 가리키면 상태 표시가 어긋난다';

-- -----------------------------------------
-- 인덱스
-- 여기가 단일 진실이다. 엔티티의 @Table(indexes)는 validate 모드에서
-- 검증되지 않아 효과가 없고 두 곳이 어긋나기만 한다.
-- -----------------------------------------

-- 1인 2매 제한. 애플리케이션 검증만으로는 동시 요청을 막지 못한다.
-- WHERE 절이 취소/만료 좌석을 제외해 재예매 시 슬롯이 자동으로 비워진다.
CREATE UNIQUE INDEX ux_seat_slot
    ON seat (phone_hash, event_id, slot_no)
 WHERE status IN ('HELD', 'SOLD');

-- 좌석 배치도 조회 및 비트맵 재생성
CREATE INDEX ix_seat_event_status ON seat (event_id, status);

-- 예약에 묶인 좌석 조회. 금액 합산과 취소 시 반환에 쓴다
CREATE INDEX ix_seat_reservation ON seat (reservation_id)
 WHERE reservation_id IS NOT NULL;

-- 홀드 만료 스케줄러. 부분 인덱스라 결제 완료된 좌석은 들어가지 않는다
CREATE INDEX ix_seat_hold_expiry ON seat (hold_expires_at)
 WHERE status = 'HELD';
