COMMENT ON COLUMN payment.charge_attempt_count IS
    '이름은 시도 횟수지만 실패해서 멱등키를 소진한 시도만 센다(확정 거절, 5xx 응답). 다음 승인 키 번호로 쓰인다. Toss가 에러 응답도 같은 Idempotency-Key로 재생하므로 이 횟수를 섞어 시도마다 키를 바꾼다';

COMMENT ON COLUMN payment.refund_attempt_count IS
    '이름은 시도 횟수지만 실패해서 멱등키를 소진한 시도만 센다(취소 시점의 첫 실패 포함). 다음 환불 키 번호이고, 한도에 도달하면 자동 재시도를 멈추고 사람이 처리한다';

COMMENT ON TABLE payment_history IS
    '결제 상태 변경 이력. from_status와 to_status가 같은 행은 상태가 안 바뀌고 남기는 메모성 기록(환불 필요 표시, 5xx 응답, 환불 실패)이라, 상태 전이만 필요한 소비자는 from_status <> to_status로 거른다. 정산 재계산의 원장 역할';
