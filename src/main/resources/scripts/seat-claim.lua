-- KEYS: seat:hold:{eventId}:{seatId} 각 좌석마다 하나
-- ARGV[1]: holdToken (이 시도의 소유권 값)
-- ARGV[2]: TTL(ms)
for i, key in ipairs(KEYS) do
    local ok = redis.call('SET', key, ARGV[1], 'NX', 'PX', ARGV[2])
    if not ok then
        for j = 1, i - 1 do
            redis.call('DEL', KEYS[j])
        end
        return 0
    end
end
return 1
