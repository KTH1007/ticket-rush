-- src/main/resources/scripts/queue-budget-reserve.lua
-- KEYS[1] = 이번 초의 예산 카운터 키
-- ARGV[1] = 요청 개수, ARGV[2] = 초당 예산, ARGV[3] = 키 TTL(초)
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local remaining = tonumber(ARGV[2]) - used
if remaining <= 0 then
    return 0
end
local granted = math.min(tonumber(ARGV[1]), remaining)
redis.call('INCRBY', KEYS[1], granted)
redis.call('EXPIRE', KEYS[1], ARGV[3])
return granted
