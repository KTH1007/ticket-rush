-- KEYS: seat:hold:{eventId}:{seatId} 각 좌석마다 하나
-- ARGV[1]: holdToken (이 값과 일치하는 키만 늘린다. 키가 없으면 되살리지 않는다)
-- ARGV[2]: TTL(ms)
for _, key in ipairs(KEYS) do
    if redis.call('GET', key) == ARGV[1] then
        redis.call('PEXPIRE', key, ARGV[2])
    end
end
return 1
