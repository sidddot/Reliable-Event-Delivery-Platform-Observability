-- Token bucket rate limiter Lua script for Redis
-- KEYS[1] = bucket key
-- ARGV[1] = max_tokens (bucket capacity)
-- ARGV[2] = refill_rate (tokens per second)
-- ARGV[3] = current_tokens (initial tokens, used only on first creation)
-- ARGV[4] = ttl (seconds) for the key expiration

local key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local init_tokens = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'timestamp')
local tokens = bucket[1]
local timestamp = bucket[2]
local now = redis.call('TIME')[1]

if tokens == false or tokens == nil then
  -- bucket does not exist, initialize
  tokens = init_tokens
  timestamp = now
else
  tokens = tonumber(tokens)
  timestamp = tonumber(timestamp)
  local elapsed = now - timestamp
  local refill = elapsed * refill_rate
  tokens = math.min(tokens + refill, max_tokens)
  timestamp = now
end

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'timestamp', timestamp)
redis.call('EXPIRE', key, ttl)

return allowed