ALTER TABLE payment ADD COLUMN charge_attempt_count integer NOT NULL DEFAULT 0;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_charge_attempt_count
        CHECK (charge_attempt_count >= 0);

COMMENT ON COLUMN payment.charge_attempt_count IS
    '승인이 확정 거절로 끝난 횟수. Toss가 에러 응답도 같은 Idempotency-Key로 재생하므로 이 횟수를 섞어 시도마다 키를 바꾼다';

-- V10에서 붙인 설명을 바꾼다: 이 횟수는 취소 시점의 첫 실패도 세고, 환불 키에도 섞는다
COMMENT ON COLUMN payment.refund_attempt_count IS
    '환불이 실패한 횟수(취소 시점의 첫 시도 포함). 한도에 도달하면 자동 재시도를 멈추고 사람이 처리한다. Toss가 에러 응답도 같은 키로 재생하므로 환불 키에도 섞어 시도마다 키를 바꾼다';
