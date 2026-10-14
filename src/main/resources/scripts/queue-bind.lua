-- src/main/resources/scripts/queue-bind.lua
-- KEYS[1] = active 키
-- ARGV[1] = 전화번호 해시(hex)
-- 반환: 1 묶였거나 이미 같은 번호, 0 다른 번호에 묶여 있음, -1 활성이 아님
local bound = redis.call('GET', KEYS[1])
if bound == false then
    return -1
end
-- 승격 직후에는 번호 대신 '1'만 들어 있다. 수명은 그대로 두고 값만 번호로 바꾼다
if bound == '1' then
    redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
    return 1
end
if bound == ARGV[1] then
    return 1
end
return 0
