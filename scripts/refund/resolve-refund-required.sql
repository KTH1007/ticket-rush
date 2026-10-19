-- 환불 필요 건(PENDING)을 처리 완료로 닫는다. 조건이 안 맞으면 0행이라 아무것도 바뀌지 않는다
-- psql -v payment_id=123 -v note='Toss 콘솔에서 환불함' -f resolve-refund-required.sql
WITH resolved AS (
    UPDATE payment
       SET status = 'FAILED', version = version + 1, updated_at = now()
     WHERE id = :payment_id AND status = 'PENDING' AND refund_required_at IS NOT NULL
 RETURNING id
), logged AS (
    INSERT INTO payment_history (payment_id, from_status, to_status, reason, created_at)
    SELECT id, 'PENDING', 'FAILED', left('운영자 환불 처리 완료: ' || :'note', 200), now() FROM resolved
 RETURNING payment_id
)
SELECT count(*) AS resolved_rows FROM logged;
