-- src/main/resources/scripts/queue-status.lua
-- KEYS[1] = token 키, KEYS[2] = active 키, KEYS[3] = lastPromotedSeq 키
-- ARGV[1] = token TTL(초). 승격된 적 없는 대기자가 조회하면 이만큼으로 다시 늘려 조회가 생존 신호가 되게 한다
local sequence = redis.call('GET', KEYS[1])
if sequence == false then
    return {0, 0, 0, 0}
end
local active = redis.call('EXISTS', KEYS[2])
local lastPromoted = redis.call('GET', KEYS[3])
if lastPromoted == false then
    lastPromoted = 0
end
-- 승격 뒤 활성이 만료된 토큰까지 갱신하면 영원히 안 죽으므로 순번이 승격 순번을 넘은 사람만 갱신한다
if active == 0 and tonumber(sequence) > tonumber(lastPromoted) then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return {1, tonumber(sequence), active, tonumber(lastPromoted)}
