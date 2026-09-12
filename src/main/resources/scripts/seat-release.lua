-- KEYS: seat:hold:{eventId}:{seatId} 각 좌석마다 하나
-- ARGV[1]: holdToken (이 값과 일치하는 키만 지운다)
for _, key in ipairs(KEYS) do
    if redis.call('GET', key) == ARGV[1] then
        redis.call('DEL', key)
    end
end
return 1
