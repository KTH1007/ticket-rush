-- KEYS[1]: reservation:lookup-fail:{reservationNo}
-- 조회 성공 시 이번 요청이 예약했던 슬롯 하나만 돌려준다. 무조건 DEL하면 동시에
-- 진행 중인 다른(진짜 공격) 요청의 실패 기록까지 같이 지워지므로 1만 뺀다.
-- 키가 없거나 0 이하면 아무것도 안 한다. 없는 키에 DECR하면 -1이 생기고, 해제가 중복되거나
-- 윈도우 경계에서 늦게 들어올 때 음수로 내려가면 그만큼 허용 횟수가 늘어난다
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count > 1 then
    redis.call('DECR', KEYS[1])
elseif count == 1 then
    redis.call('DEL', KEYS[1])
end
return 1
