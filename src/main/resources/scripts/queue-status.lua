-- src/main/resources/scripts/queue-status.lua
-- KEYS[1] = token 키, KEYS[2] = active 키, KEYS[3] = lastPromotedSeq 키
-- ARGV[1] = token TTL(초). 대기 중인 사람이 조회하면 이만큼으로 다시 늘려 조회가 생존 신호가 되게 한다
local sequence = redis.call('GET', KEYS[1])
if sequence == false then
    return {0, 0, 0, 0}
end
local active = redis.call('EXISTS', KEYS[2])
if active == 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local lastPromoted = redis.call('GET', KEYS[3])
if lastPromoted == false then
    lastPromoted = 0
end
return {1, tonumber(sequence), active, tonumber(lastPromoted)}
