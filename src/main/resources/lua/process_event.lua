-- KEYS[1] = "lb:{leaderboardId}:idem:{eventUuid}"
-- KEYS[2] = "lb:{leaderboardId}:z"
-- KEYS[3] = "lb:{leaderboardId}:events"
-- ARGV[1] = userId
-- ARGV[2] = deltaScore
-- ARGV[3] = ttlSeconds
-- ARGV[4] = idempotency record (e.g. "v2:<payloadHash>:<processedAtEpochMillis>")

-- 1) Idempotency check
local existing = redis.call('GET', KEYS[1])
if existing then
    -- Replayed request.
    -- Java layer parses the stored idempotency record and compares its hash with the incoming hash:
    -- - same hash: replayed=true
    -- - different hash: IDEMPOTENCY_KEY_REUSE_MISMATCH (409)
    return {0, existing}
end

-- 2) Increase user score in leaderboard ZSET
local newScore = redis.call('ZINCRBY', KEYS[2], tonumber(ARGV[2]), ARGV[1])

-- 3) Persist idempotency key with TTL
redis.call('SET', KEYS[1], ARGV[4], 'EX', tonumber(ARGV[3]))

-- 4) Append audit stream entry
-- Use bounded stream to avoid unbounded memory growth.
redis.call('XADD', KEYS[3], 'MAXLEN', '~', 100000, '*', 'userId', ARGV[1], 'delta', ARGV[2])

-- isNew=1, newScore returned as string from Redis
return {1, newScore}
