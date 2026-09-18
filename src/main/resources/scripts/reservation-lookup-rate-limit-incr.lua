-- KEYS[1]: reservation:lookup-fail:{reservationNo}
-- ARGV[1]: TTL(초). 첫 실패일 때만 설정해 고정 윈도우를 만든다
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
