COMMENT ON COLUMN payment.status IS
    'PENDING(PG 승인 대기) -> SUCCESS(승인 완료) 또는 FAILED(PG 거절, 조회로 미승인을 확정한 경우 포함). FAILED는 같은 예약으로 다시 시도하면 PENDING으로 되살아난다. 응답을 못 받은 타임아웃은 FAILED가 아니라 PENDING으로 남아 회수 스케줄러가 처리한다. 성공 후 CANCELED(취소 접수)를 거쳐 REFUNDED(환불 완료)로 간다. FAILED와 CANCELED는 원인이 다르다 - 전자는 결제 자체가 안 된 것, 후자는 결제된 걸 되돌리는 것이다';
