-- event_id 조회는 uk_seat_ordinal이 앞쪽 컬럼으로 받고, 매진 확인의 status 부정 조건은 이 인덱스를 타지 않는다.
-- 겹치는 인덱스가 있으면 플래너가 이쪽을 골라 쓰기 WAL과 크기만 더 든다
DROP INDEX ix_seat_event_status;

-- 이 인덱스를 타는 쿼리가 없다. 정산을 만들 때 실제 쿼리에 맞춰 다시 설계한다
DROP INDEX ix_payment_settlement;

COMMENT ON CONSTRAINT uk_seat_ordinal ON seat IS
    '비트맵 인덱스 중복 방지. 두 좌석이 같은 비트를 가리키면 상태 표시가 어긋난다. event_id 단독 조회와 ordinal 정렬도 이 인덱스가 맡는다';
