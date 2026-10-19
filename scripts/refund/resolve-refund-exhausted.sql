-- 환불 시도를 소진한 건(CANCELED)을 수동 환불 완료로 닫는다. max_attempts는 설정 refund-max-attempts 값이다
-- psql -v payment_id=123 -v max_attempts=5 -v note='Toss 콘솔에서 환불함' -f resolve-refund-exhausted.sql
WITH resolved AS (
    UPDATE payment
       SET status = 'REFUNDED', version = version + 1, updated_at = now()
     WHERE id = :payment_id AND status = 'CANCELED' AND refund_attempt_count >= :max_attempts
 RETURNING id
), logged AS (
    INSERT INTO payment_history (payment_id, from_status, to_status, reason, created_at)
    SELECT id, 'CANCELED', 'REFUNDED', left('운영자 수동 환불 완료: ' || :'note', 200), now() FROM resolved
 RETURNING payment_id
)
SELECT count(*) AS resolved_rows FROM logged;
