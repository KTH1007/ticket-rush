-- KEYS[1] = seq 키, KEYS[2] = waiting ZSET 키, KEYS[3] = token 키, KEYS[4] = 활성 이벤트 집합 키, KEYS[5] = 오픈 시각 키(epoch ms), KEYS[6] = 시작 시각 키(epoch ms), KEYS[7] = IP별 발급 수 키
-- ARGV[1] = 토큰, ARGV[2] = token TTL(초), ARGV[3] = eventId, ARGV[4] = 일정 캐시 TTL(초), ARGV[5] = 오픈 시각 힌트(epoch ms, 없으면 ''), ARGV[6] = 시작 시각 힌트(epoch ms, 없으면 ''), ARGV[7] = IP당 발급 상한, ARGV[8] = 발급 수 윈도우(초)
-- 반환: {1, 순번} 등록됨, {0, 오픈 시각} 오픈 전, {-1, 0} 일정을 모름, {-2, 시작 시각} 공연 시작 뒤, {-3, 남은 ms} 이 IP의 발급 상한 도달
local opensAt = redis.call('GET', KEYS[5])
local startsAt = redis.call('GET', KEYS[6])
if opensAt == false or startsAt == false then
    if ARGV[5] == '' or ARGV[6] == '' then
        return {-1, 0}
    end
    opensAt = ARGV[5]
    startsAt = ARGV[6]
    redis.call('SET', KEYS[5], opensAt, 'EX', ARGV[4])
    redis.call('SET', KEYS[6], startsAt, 'EX', ARGV[4])
end
-- 인스턴스 시계 차이로 오픈 순간이 어긋나지 않게 앱 시계가 아니라 Redis 시계로 판정한다
local now = redis.call('TIME')
local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
if nowMs < tonumber(opensAt) then
    return {0, tonumber(opensAt)}
end
-- 시작한 공연에는 새로 줄을 세우지 않는다. 이미 줄 선 사람은 건드리지 않는다
if nowMs >= tonumber(startsAt) then
    return {-2, tonumber(startsAt)}
end
-- 이 IP가 이번 윈도우에 받은 토큰 수. 발급이 확정된 요청만 센다(오픈 전, 시작 뒤 거절은 세지 않는다)
local issued = tonumber(redis.call('GET', KEYS[7]) or '0')
if issued >= tonumber(ARGV[7]) then
    return {-3, redis.call('PTTL', KEYS[7])}
end
if redis.call('INCR', KEYS[7]) == 1 then
    redis.call('EXPIRE', KEYS[7], ARGV[8])
end
local seq = redis.call('INCR', KEYS[1])
redis.call('ZADD', KEYS[2], seq, ARGV[1])
redis.call('SET', KEYS[3], seq, 'EX', ARGV[2])
redis.call('SADD', KEYS[4], ARGV[3])
return {1, seq}
