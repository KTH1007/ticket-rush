-- KEYS[1] = seq 키, KEYS[2] = waiting ZSET 키, KEYS[3] = token 키
-- ARGV[1] = 토큰, ARGV[2] = token TTL(초)
local seq = redis.call('INCR', KEYS[1])
redis.call('ZADD', KEYS[2], seq, ARGV[1])
redis.call('SET', KEYS[3], seq, 'EX', ARGV[2])
return seq
