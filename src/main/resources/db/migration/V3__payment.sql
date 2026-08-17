CREATE TABLE payment (
    id                bigserial PRIMARY KEY,
    reservation_id    bigint NOT NULL REFERENCES reservation(id),
    pg_transaction_id varchar(100),
    amount            integer NOT NULL,
    status            varchar(20) NOT NULL,
    paid_at           timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,

    CONSTRAINT uk_payment_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_payment_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'CANCELED', 'REFUNDED'))
);

COMMENT ON TABLE payment IS '결제. 예약당 1건';
COMMENT ON COLUMN payment.paid_at IS
    '실제 결제 확정 시각. 정산 집계의 기준이다. created_at은 결제 시도 생성 시각이라 정산 의미와 어긋난다';
COMMENT ON COLUMN payment.version IS
    '낙관적 락. 홀드 만료 스케줄러와 결제 성공 콜백이 거의 동시에 들어오는 경쟁을 감지한다';
COMMENT ON COLUMN payment.status IS
    'PENDING(PG 승인 대기) -> SUCCESS(승인 완료) 또는 FAILED(PG 거절/타임아웃). 성공 후 CANCELED(취소 접수)를 거쳐 REFUNDED(환불 완료)로 간다. FAILED와 CANCELED는 원인이 다르다 - 전자는 결제 자체가 안 된 것, 후자는 결제된 걸 되돌리는 것이다';
COMMENT ON CONSTRAINT uk_payment_reservation ON payment IS
    '예약당 결제 1건 전제. 부분 취소나 재결제를 허용하려면 이 제약을 빼고 status로 유효 건을 구분해야 한다';

CREATE TABLE payment_history (
    id          bigserial PRIMARY KEY,
    payment_id  bigint NOT NULL REFERENCES payment(id),
    from_status varchar(20),
    to_status   varchar(20) NOT NULL,
    reason      varchar(200),
    created_at  timestamptz NOT NULL
);

COMMENT ON TABLE payment_history IS
    '결제 상태 변경 이력. 정산 재계산의 원장 역할';

-- PostgreSQL은 FK 컬럼에 인덱스를 자동 생성하지 않는다(MySQL과 다른 지점).
-- 특정 결제의 이력을 시간순으로 훑는 조회에 쓴다
CREATE INDEX ix_payment_history_payment
    ON payment_history (payment_id, created_at);

-- 정산은 결제 확정 시각 기준으로 집계한다
CREATE INDEX ix_payment_settlement ON payment (paid_at)
 WHERE status = 'SUCCESS';
