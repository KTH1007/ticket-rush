ALTER TABLE payment ADD COLUMN refund_required_at timestamptz;

CREATE INDEX ix_payment_refund_required ON payment (refund_required_at)
 WHERE refund_required_at IS NOT NULL;

COMMENT ON COLUMN payment.refund_required_at IS
    'Toss는 승인(DONE)했지만 예약이 만료돼 고객에게 환불이 필요하다고 판정한 시각. 자동 환불은 하지 않고 사람이 처리하며, 표시된 PENDING 행은 회수 스케줄러가 다시 조회하지 않는다';
COMMENT ON INDEX ix_payment_refund_required IS
    '환불 필요로 표시된 결제만 담는 부분 인덱스. 미처리 건수 지표가 전체 결제를 훑지 않게 한다';
