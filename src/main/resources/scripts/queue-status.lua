-- src/main/resources/scripts/queue-status.lua
-- KEYS[1] = token 키, KEYS[2] = active 키, KEYS[3] = lastPromotedSeq 키
local sequence = redis.call('GET', KEYS[1])
if sequence == false then
    return {0, 0, 0, 0}
end
local active = redis.call('EXISTS', KEYS[2])
local lastPromoted = redis.call('GET', KEYS[3])
if lastPromoted == false then
    lastPromoted = 0
end
return {1, tonumber(sequence), active, tonumber(lastPromoted)}
