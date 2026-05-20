-- KEYS[1] = rate limit key "rl:{apiKeyId}:{windowStart}"
-- KEYS[2] = daily quota key "qt:{apiKeyId}:{dayStart}"
-- ARGV[1] = rate limit per second
-- ARGV[2] = rate limit TTL seconds
-- ARGV[3] = daily quota
-- ARGV[4] = daily quota TTL seconds
--
-- 반환값 규약:
-- { 1,  rateCurrent, dailyCurrent }  -> 요청 허용
-- { 0,  rateCurrent, dailyCurrent }  -> 초당 rate limit 초과
-- {-1,  rateCurrent, dailyCurrent }  -> daily quota 초과
--
-- 이 스크립트는 "요청 1건이 들어왔을 때" 아래 순서로 처리한다.
-- 1) 먼저 초당 rate counter를 올린다.
-- 2) 초당 limit을 넘었으면 즉시 차단한다.
--    이 경우 daily quota counter는 올리지 않는다.
-- 3) 초당 limit 안이면 현재 daily quota 사용량을 확인한다.
-- 4) daily quota가 남아 있으면 그때 daily quota counter를 1 증가시킨다.
--
-- 즉, 증가 순서는 아래와 같다.
-- - rate count: 모든 요청 시도마다 먼저 증가
-- - daily quota count: 최종 허용되는 요청에서만 증가

local rate_limit = tonumber(ARGV[1])
local rate_ttl = tonumber(ARGV[2])
local daily_quota = tonumber(ARGV[3])
local daily_ttl = tonumber(ARGV[4])

-- 1) 현재 초(second) 버킷의 요청 수를 먼저 1 증가시킨다.
--    예: 같은 apiKey로 같은 1초 안에 3번째 요청이면 rate_current = 3
local rate_current = redis.call('INCR', KEYS[1])

-- 이 초 버킷이 처음 만들어진 순간이면 만료 시간도 같이 설정한다.
-- TTL이 지나면 다음 초에는 새로운 rate limit 버킷이 시작된다.
if rate_current == 1 then
    redis.call('EXPIRE', KEYS[1], rate_ttl)
end

-- 2) rate limit 검사
--    초당 허용치보다 커졌으면 즉시 차단한다.
--    이때는 daily quota를 소모하면 안 되므로 daily 키는 읽기만 하고 증가시키지 않는다.
if rate_current > rate_limit then
    local daily_current = tonumber(redis.call('GET', KEYS[2]) or '0')
    return {0, rate_current, daily_current}
end

-- 3) 현재 일(day) 버킷의 누적 사용량을 읽는다.
--    아직 키가 없으면 오늘 첫 요청이므로 0으로 본다.
local daily_current = tonumber(redis.call('GET', KEYS[2]) or '0')

-- 오늘 quota를 이미 다 썼으면 차단한다.
-- 이 경우도 quota를 더 올리면 안 되므로 증가 없이 바로 반환한다.
if daily_current >= daily_quota then
    return {-1, rate_current, daily_current}
end

-- 4) 여기까지 왔다는 건
--    - 초당 rate limit 통과
--    - daily quota도 남아 있음
--    이므로 이 요청은 최종 허용된다.
--
--    이제서야 daily quota 사용량을 1 증가시킨다.
--    예: 오늘 57번째 허용 요청이면 daily_current = 57
daily_current = redis.call('INCR', KEYS[2])

-- 오늘 버킷이 처음 만들어진 순간이면 자정까지 TTL을 설정한다.
-- TTL이 끝나면 다음 날 새로운 daily quota 버킷이 시작된다.
if daily_current == 1 then
    redis.call('EXPIRE', KEYS[2], daily_ttl)
end

-- 허용 결과 반환
return {1, rate_current, daily_current}
