CREATE TABLE reservation (
    id              bigserial PRIMARY KEY,
    reservation_no  varchar(12),
    idempotency_key uuid NOT NULL,
    event_id        bigint NOT NULL REFERENCES event(id),
    phone_hash      bytea NOT NULL,
    quantity        smallint NOT NULL,
    amount          integer NOT NULL,
    status          varchar(20) NOT NULL,
    hold_token      uuid NOT NULL,
    hold_expires_at timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,

    CONSTRAINT uk_reservation_no   UNIQUE (reservation_no),
    CONSTRAINT uk_reservation_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_reservation_qty  CHECK (quantity BETWEEN 1 AND 2),
    CONSTRAINT ck_reservation_status
        CHECK (status IN ('HOLDING', 'PAID', 'CANCELED', 'EXPIRED')),
    -- 발급 시점 강제. PAID로 전이했는데 예매번호가 없는 상태를 막는다
    CONSTRAINT ck_reservation_no_on_paid
        CHECK (status <> 'PAID' OR reservation_no IS NOT NULL)
);

COMMENT ON TABLE reservation IS '예약. 좌석 1~2석을 묶는 단위';
COMMENT ON COLUMN reservation.reservation_no IS
    'SecureRandom 12자리. 결제 완료 시점에 발급하므로 HOLDING 상태에서는 NULL이다. PostgreSQL의 UNIQUE는 NULL을 여러 개 허용해 홀드 중인 예약이 여러 건이어도 문제없다. 예매번호+전화번호 조합 인증의 한 축이라 순차값이면 안 된다';
COMMENT ON COLUMN reservation.idempotency_key IS
    '결제 요청의 멱등성 키. 예매번호는 결제 완료 후에야 발급돼 이 시점에 쓸 수 없고, PK를 쓰면 외부 PG에 보내는 키가 예측 가능해진다';
COMMENT ON COLUMN reservation.hold_token IS
    '홀드 소유권 증명. 결제 확정 직전 Redis에서 이 토큰이 여전히 유효한지 확인한다';
COMMENT ON COLUMN reservation.quantity IS
    'seat에서 세면 알 수 있는 파생값이지만 비정규화한다. 예매 내역 조회에서 좌석 조인 없이 매수를 보여줄 수 있고 CHECK로 2매 초과를 한 겹 더 막는다';
COMMENT ON COLUMN reservation.status IS
    'HOLDING(좌석 선점, 결제 대기) -> PAID(결제 확정) 또는 CANCELED(사용자 취소)/EXPIRED(홀드 만료). CANCELED와 EXPIRED를 나누는 이유는 사용자가 직접 취소한 것과 방치돼 시간 초과로 풀린 것을 정산/통계에서 구분해야 하기 때문이다';
COMMENT ON CONSTRAINT ck_reservation_no_on_paid ON reservation IS
    '결제 확정 시 예매번호 발급을 DB가 강제한다. 애플리케이션이 발급을 빠뜨리면 커밋이 거부된다';

-- 예매번호가 NULL인 홀드 중 예약은 조회 대상이 아니다
CREATE INDEX ix_reservation_lookup ON reservation (reservation_no, phone_hash)
 WHERE reservation_no IS NOT NULL;

CREATE INDEX ix_reservation_hold_expiry ON reservation (hold_expires_at)
 WHERE status = 'HOLDING';

-- seat.reservation_id FK. reservation 테이블이 만들어진 뒤에 건다.
-- DEFERRABLE INITIALLY DEFERRED로 두면 좌석 선점 UPDATE가 예약 INSERT보다
-- 먼저 실행돼도 커밋 시점에 검증하므로 순서 제약이 사라진다.
ALTER TABLE seat
    ADD CONSTRAINT fk_seat_reservation
    FOREIGN KEY (reservation_id) REFERENCES reservation(id)
    DEFERRABLE INITIALLY DEFERRED;
