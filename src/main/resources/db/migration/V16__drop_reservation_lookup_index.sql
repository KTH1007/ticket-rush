-- 예매번호 조회는 uk_reservation_no만으로 풀리고 전화번호 해시 비교는 앱에서 한다.
-- 겹치면 플래너가 이쪽을 골라 쓰기 WAL과 크기만 더 든다
DROP INDEX ix_reservation_lookup;

COMMENT ON CONSTRAINT uk_reservation_no ON reservation IS
    '예매번호 유일성과 예매번호 조회를 함께 맡는다. 전화번호 해시는 앱에서 비교하므로 (reservation_no, phone_hash) 복합 인덱스는 두지 않는다';
