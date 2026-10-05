CREATE INDEX ix_payment_stale_pending ON payment (updated_at)
 WHERE status = 'PENDING';

COMMENT ON INDEX ix_payment_stale_pending IS
    '회수 스케줄러가 오래된 PENDING 결제를 updatedAt 순서로 찾는 인덱스. 확정된 행이 대부분이라 PENDING만 담는 부분 인덱스로 작게 유지한다';
