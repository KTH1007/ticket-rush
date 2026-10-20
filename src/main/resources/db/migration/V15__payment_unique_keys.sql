CREATE UNIQUE INDEX uk_payment_toss_payment_key ON payment (toss_payment_key)
 WHERE toss_payment_key IS NOT NULL;

CREATE UNIQUE INDEX uk_payment_pg_transaction_id ON payment (pg_transaction_id)
 WHERE pg_transaction_id IS NOT NULL;

COMMENT ON INDEX uk_payment_toss_payment_key IS
    '한 Toss 결제 키를 여러 예약이 쓰는 것을 DB에서도 막는다. 키는 클라이언트가 보내는 값이라 위조나 재사용을 방어한다. 아직 키가 없는 행(NULL)은 여러 개여도 된다';
COMMENT ON INDEX uk_payment_pg_transaction_id IS
    '한 PG 거래를 여러 결제 행이 쓰는 것을 막는다. 승인 전에는 NULL이라 여러 개여도 된다';
