ALTER TABLE payment ADD COLUMN refund_attempt_count integer NOT NULL DEFAULT 0;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_refund_attempt_count
        CHECK (refund_attempt_count >= 0);

CREATE INDEX ix_payment_stale_canceled ON payment (updated_at)
 WHERE status = 'CANCELED';

COMMENT ON COLUMN payment.refund_attempt_count IS
    '환불 재시도 스케줄러가 실패한 횟수. 한도에 도달하면 자동 재시도를 멈추고 사람이 처리한다';
COMMENT ON INDEX ix_payment_stale_canceled IS
    '환불 재시도 스케줄러가 오래된 CANCELED 결제를 updatedAt 순서로 찾는 인덱스. 환불이 끝난 행(REFUNDED)이 대부분이라 CANCELED만 담는 부분 인덱스로 작게 유지한다';
