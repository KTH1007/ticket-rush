-- 환불 필요 건과 마지막 이력 사유. 정리하기 전에 Toss 콘솔에서 실제로 환불됐는지 확인한다
-- psql -f list-refund-required.sql
SELECT p.id, p.reservation_id, p.amount, p.toss_payment_key, p.refund_required_at,
       (SELECT h.reason FROM payment_history h WHERE h.payment_id = p.id ORDER BY h.id DESC LIMIT 1) AS last_reason
  FROM payment p
 WHERE p.status = 'PENDING' AND p.refund_required_at IS NOT NULL
 ORDER BY p.refund_required_at;
