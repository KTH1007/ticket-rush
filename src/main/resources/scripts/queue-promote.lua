-- src/main/resources/scripts/queue-promote.lua
-- KEYS[1] = waiting ZSET, KEYS[2] = lastPromotedSeq 키, KEYS[3] = 활성 이벤트 집합 키
-- ARGV[1] = 이번 호출의 최대 승격 수, ARGV[2] = active TTL(초), ARGV[3] = eventId
-- ARGV[4] = 초당 예산, ARGV[5] = 예산 키 TTL(초), ARGV[6] = 예산 키 접두사
-- 인스턴스마다 다른 초를 쓰면 같은 순간에 예산이 겹쳐 쓰이므로 초를 Redis 시계로 만들고, 실제로 승격한 수만큼만 차감한다
local now = redis.call('TIME')
local budgetKey = ARGV[6] .. now[1]
local remaining = tonumber(ARGV[4]) - tonumber(redis.call('GET', budgetKey) or '0')
if remaining <= 0 then
    return {}
end

local needed = math.min(tonumber(ARGV[1]), remaining)
local tokens = {}
local lastSeq = 0
local promoted = 0
local totalPopped = 0
local maxPops = needed * 10  -- 만료된 유령이 몰려 있어도 무한정 안 돌게 하는 안전장치

while promoted < needed and totalPopped < maxPops do
    local popped = redis.call('ZPOPMIN', KEYS[1], needed - promoted)
    if #popped == 0 then
        break
    end
    totalPopped = totalPopped + (#popped / 2)

    for i = 1, #popped, 2 do
        local token = popped[i]
        local score = tonumber(popped[i + 1])
        if score > lastSeq then
            lastSeq = score
        end

        local tokenKey = 'queue:' .. ARGV[3] .. ':token:' .. token
        if redis.call('EXISTS', tokenKey) == 1 then
            redis.call('SET', 'queue:' .. ARGV[3] .. ':active:' .. token, '1', 'EX', ARGV[2])
            table.insert(tokens, token)
            promoted = promoted + 1
        end
    end
end

if promoted > 0 then
    redis.call('INCRBY', budgetKey, promoted)
    redis.call('EXPIRE', budgetKey, ARGV[5])
end

if lastSeq > 0 then
    redis.call('SET', KEYS[2], lastSeq)
end

if redis.call('ZCARD', KEYS[1]) == 0 then
    redis.call('SREM', KEYS[3], ARGV[3])
end

return tokens
