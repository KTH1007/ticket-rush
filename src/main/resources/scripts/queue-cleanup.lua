-- src/main/resources/scripts/queue-cleanup.lua
-- KEYS[1] = waiting ZSET, KEYS[2] = seq 키, KEYS[3] = lastPromotedSeq 키, KEYS[4] = 활성 이벤트 집합 키, KEYS[5] = 정리 표시 키
-- ARGV[1] = eventId, ARGV[2] = 이 시각(epoch ms) 이전에는 지우지 않는다, ARGV[3] = 정리 표시 키 TTL(초)
-- 반환: 지운 키 수(0 이상), 아직 이르면 -1
-- 인스턴스마다 시계가 달라 앱 시계만 믿으면 살아 있는 대기열을 일찍 지울 수 있어서 Redis 시계로 다시 확인한다
local now = redis.call('TIME')
local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
if nowMs < tonumber(ARGV[2]) then
    return -1
end

-- 큰 ZSET을 DEL로 지우면 메모리를 해제하는 동안 서버 전체가 멈춘다. UNLINK는 해제를 백그라운드로 넘긴다
local removed = redis.call('UNLINK', KEYS[1], KEYS[2], KEYS[3])
redis.call('SREM', KEYS[4], ARGV[1])
-- token 키는 대기자 수만큼 있어 여기서 지우지 않는다. 표시를 남겨 조회가 무효로 보게 한다
redis.call('SET', KEYS[5], '1', 'EX', ARGV[3])
return removed
