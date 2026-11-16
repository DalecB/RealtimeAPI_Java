-- KEYS = rate(rl:{apiKeyId}:{windowStart}), quota(qt:{apiKeyId}:{dayStart}) / ARGV = ratePerSecond, rateTtlSeconds, dailyQuota, dailyTtlSeconds
-- 거절된 요청도 rate는 세지만 daily quota는 최종 허용된 요청만 센다.

local rate_limit = tonumber(ARGV[1])
local rate_ttl = tonumber(ARGV[2])
local daily_quota = tonumber(ARGV[3])
local daily_ttl = tonumber(ARGV[4])

local rate_current = redis.call('INCR', KEYS[1])

if rate_current == 1 then
    redis.call('EXPIRE', KEYS[1], rate_ttl)
end

if rate_current > rate_limit then
    local daily_current = tonumber(redis.call('GET', KEYS[2]) or '0')
    return {0, rate_current, daily_current}
end

local daily_current = tonumber(redis.call('GET', KEYS[2]) or '0')

if daily_current >= daily_quota then
    return {-1, rate_current, daily_current}
end

daily_current = redis.call('INCR', KEYS[2])

if daily_current == 1 then
    redis.call('EXPIRE', KEYS[2], daily_ttl)
end

return {1, rate_current, daily_current}
