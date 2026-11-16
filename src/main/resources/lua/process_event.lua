-- KEYS[1] = "lb:{leaderboardId}:idem:{eventUuid}"
-- KEYS[2] = "lb:{leaderboardId}:z"
-- KEYS[3] = "lb:{leaderboardId}:events"
-- KEYS[4] = "lb:{leaderboardId}:warm"
-- ARGV[1] = userId
-- ARGV[2] = deltaScore
-- ARGV[3] = ttlSeconds
-- ARGV[4] = idempotency record (e.g. "v2:<payloadHash>:<processedAtEpochMillis>")
-- ARGV[5] = apiKeyId
-- ARGV[6] = idempotency key

-- 0) Warm check
-- The warm key disappears together with the ranking when Redis loses its data.
-- Without it, a score would land on an empty ranking and block recovery from the snapshot.
if redis.call('EXISTS', KEYS[4]) == 0 then
    return {-1, 'cold'}
end

-- 1) Idempotency check
local existing = redis.call('GET', KEYS[1])
if existing then
    -- Replayed request.
    -- Java layer parses the stored idempotency record and compares its hash with the incoming hash:
    local existingHash = string.match(existing, "^v2:([^:]+):")
    local incomingHash = string.match(ARGV[4], "^v2:([^:]+):")

    if existingHash and incomingHash and existingHash ~= incomingHash then
        redis.call(
            'XADD', KEYS[3], 
            'MAXLEN', '~', 100000, '*', 
            'type', 'conflict', 
            'userId', ARGV[1], 
            'delta', ARGV[2], 
            'apiKeyId', ARGV[5], 
            'idempotencyKey', ARGV[6]
        )
    end
    return {0, existing}
end

-- 2) Increase user score in leaderboard ZSET
local newScore = redis.call('ZINCRBY', KEYS[2], tonumber(ARGV[2]), ARGV[1])

-- 3) Persist idempotency key with TTL
redis.call('SET', KEYS[1], ARGV[4], 'EX', tonumber(ARGV[3]))

-- 4) Append audit stream entry
-- Use bounded stream to avoid unbounded memory growth.
redis.call(
    'XADD', KEYS[3], 
    'MAXLEN', '~', 100000, '*', 
    'type', 'new', 
    'userId', ARGV[1], 
    'delta', ARGV[2], 
    'apiKeyId', ARGV[5], 
    'idempotencyKey', ARGV[6]
)

-- isNew=1, newScore returned as string from Redis
return {1, newScore}
