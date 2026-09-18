-- KEYS[1]: reservation:lookup-fail:{reservationNo}
-- ARGV[1]: maxAttempts
-- ARGV[2]: TTL(초), 첫 예약일 때만 설정해 고정 윈도우를 만든다
-- 확인(GET)과 예약(INCR)을 한 스크립트 안에서 원자적으로 묶어, 동시 요청이 전부
-- 임계치 미만인 stale한 값을 읽고 통과해버리는 경쟁 상태를 막는다
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count >= tonumber(ARGV[1]) then
    return 0
end
local newCount = redis.call('INCR', KEYS[1])
if newCount == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return 1
