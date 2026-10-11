-- KEYS[1] = seq 키, KEYS[2] = waiting ZSET 키, KEYS[3] = token 키, KEYS[4] = 활성 이벤트 집합 키, KEYS[5] = 오픈 시각 키(epoch ms)
-- ARGV[1] = 토큰, ARGV[2] = token TTL(초), ARGV[3] = eventId, ARGV[4] = 오픈 시각 캐시 TTL(초), ARGV[5] = 오픈 시각 힌트(epoch ms, 없으면 '')
-- 반환: {1, 순번} 등록됨, {0, 오픈 시각} 오픈 전, {-1, 0} 오픈 시각을 모름
local opensAt = redis.call('GET', KEYS[5])
if opensAt == false then
    if ARGV[5] == '' then
        return {-1, 0}
    end
    opensAt = ARGV[5]
    redis.call('SET', KEYS[5], opensAt, 'EX', ARGV[4])
end
-- 인스턴스 시계 차이로 오픈 순간이 어긋나지 않게 앱 시계가 아니라 Redis 시계로 판정한다
local now = redis.call('TIME')
local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
if nowMs < tonumber(opensAt) then
    return {0, tonumber(opensAt)}
end
local seq = redis.call('INCR', KEYS[1])
redis.call('ZADD', KEYS[2], seq, ARGV[1])
redis.call('SET', KEYS[3], seq, 'EX', ARGV[2])
redis.call('SADD', KEYS[4], ARGV[3])
return {1, seq}
