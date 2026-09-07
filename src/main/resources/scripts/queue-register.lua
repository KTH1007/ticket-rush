-- KEYS[1] = seq 키, KEYS[2] = waiting ZSET 키, KEYS[3] = token 키, KEYS[4] = 활성 이벤트 집합 키
-- ARGV[1] = 토큰, ARGV[2] = token TTL(초), ARGV[3] = eventId
local seq = redis.call('INCR', KEYS[1])
redis.call('ZADD', KEYS[2], seq, ARGV[1])
redis.call('SET', KEYS[3], seq, 'EX', ARGV[2])
redis.call('SADD', KEYS[4], ARGV[3])
return seq

