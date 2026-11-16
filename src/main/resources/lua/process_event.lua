-- KEYS = idempotency, ranking(z), audit stream / ARGV = userId, deltaScore, ttlSeconds, idempotencyRecord, apiKeyId, idempotencyKey, auditStreamMaxLen

local existing = redis.call('GET', KEYS[1])
if existing then
    -- v2:<hash>:<ms> 형식은 EventCommandRepositoryAdapter도 파싱한다. 바꾸면 양쪽을 같이 고친다.
    local existingHash = string.match(existing, "^v2:([^:]+):")
    local incomingHash = string.match(ARGV[4], "^v2:([^:]+):")

    if existingHash and incomingHash and existingHash ~= incomingHash then
        redis.call(
            'XADD', KEYS[3], 
            'MAXLEN', '~', ARGV[7], '*', 
            'type', 'conflict', 
            'userId', ARGV[1], 
            'delta', ARGV[2], 
            'apiKeyId', ARGV[5], 
            'idempotencyKey', ARGV[6]
        )
    end
    return {0, existing}
end

local newScore = redis.call('ZINCRBY', KEYS[2], tonumber(ARGV[2]), ARGV[1])

redis.call('SET', KEYS[1], ARGV[4], 'EX', tonumber(ARGV[3]))

redis.call(
    'XADD', KEYS[3], 
    'MAXLEN', '~', ARGV[7], '*', 
    'type', 'new', 
    'userId', ARGV[1], 
    'delta', ARGV[2], 
    'apiKeyId', ARGV[5], 
    'idempotencyKey', ARGV[6]
)

return {1, newScore}
