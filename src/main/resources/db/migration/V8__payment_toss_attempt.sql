ALTER TABLE payment ADD COLUMN toss_payment_key varchar(200);
ALTER TABLE payment ADD COLUMN toss_order_id varchar(64);

COMMENT ON COLUMN payment.toss_payment_key IS
    '클레임(PENDING) 시점에 받아둔 토스 paymentKey. 회수 스케줄러가 같은 값으로 재시도할 때 씀';
COMMENT ON COLUMN payment.toss_order_id IS
    '클레임 시점의 orderId(reservation.idempotencyKey와 동일). 회수 스케줄러가 재시도할 때 씀';
