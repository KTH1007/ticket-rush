-- 환불 시도를 소진한 건과 마지막 이력 사유. max_attempts는 설정 refund-max-attempts 값이다
-- psql -v max_attempts=5 -f list-refund-exhausted.sql
SELECT p.id, p.reservation_id, p.amount, p.pg_transaction_id, p.refund_attempt_count, p.updated_at,
       (SELECT h.reason FROM payment_history h WHERE h.payment_id = p.id ORDER BY h.id DESC LIMIT 1) AS last_reason
  FROM payment p
 WHERE p.status = 'CANCELED' AND p.refund_attempt_count >= :max_attempts
 ORDER BY p.updated_at;
