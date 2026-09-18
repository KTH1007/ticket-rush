-- KEYS[1]: reservation:lookup-fail:{reservationNo}
-- 조회 성공 시 이번 요청이 예약했던 슬롯 하나만 돌려준다. 무조건 DEL하면 동시에
-- 진행 중인 다른(진짜 공격) 요청의 실패 기록까지 같이 지워지므로 1만 뺀다.
-- 키가 이미 만료돼 없어졌으면 새로 만들지 않는다(DECR은 없는 키에 -1을 만들어버림)
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('DECR', KEYS[1])
end
return 1
