-- KEYS[1] = waiting ZSET, KEYS[2] = lastPromotedSeq 키, KEYS[3] = 활성 이벤트 집합 키
-- ARGV[1] = count, ARGV[2] = active TTL(초), ARGV[3] = eventId
local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
local tokens = {}
local lastSeq = 0

for i = 1, #popped, 2 do
    local token = popped[i]
    local score = tonumber(popped[i + 1])
    redis.call('SET', 'queue:' .. ARGV[3] .. ':active:' .. token, '1', 'EX', ARGV[2])
    table.insert(tokens, token)
    if score > lastSeq then
        lastSeq = score
    end
end

if lastSeq > 0 then
    redis.call('SET', KEYS[2], lastSeq)
end

if redis.call('ZCARD', KEYS[1]) == 0 then
    redis.call('SREM', KEYS[3], ARGV[3])
end

return tokens
