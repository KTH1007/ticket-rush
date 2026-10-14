-- KEYS: seat:hold:{eventId}:{seatId} 각 좌석마다 하나
-- ARGV[1]: holdToken (이 값과 일치하는 키만 줄인다)
-- ARGV[2]: TTL(ms). 남은 시간이 이보다 길 때만 줄이고 늘리지는 않는다
for _, key in ipairs(KEYS) do
    if redis.call('GET', key) == ARGV[1] then
        local ttl = redis.call('PTTL', key)
        if ttl < 0 or ttl > tonumber(ARGV[2]) then
            redis.call('PEXPIRE', key, ARGV[2])
        end
    end
end
return 1
