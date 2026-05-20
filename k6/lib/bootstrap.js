import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { uuidv4 } from './uuid.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USER_COUNT = Number(__ENV.USER_COUNT || 200);
const BENCHMARK_RATE_LIMIT_PER_SEC = positiveIntEnv('BENCHMARK_RATE_LIMIT_PER_SEC', 1_000_000);
const BENCHMARK_DAILY_QUOTA = positiveIntEnv('BENCHMARK_DAILY_QUOTA', 2_000_000_000);
const BOOTSTRAP_READY_TIMEOUT_MS = positiveIntEnv('BOOTSTRAP_READY_TIMEOUT_MS', 60_000);
const BOOTSTRAP_READY_INTERVAL_MS = positiveIntEnv('BOOTSTRAP_READY_INTERVAL_MS', 1_000);

function jsonHeaders(extra = {}) {
  return { headers: { 'Content-Type': 'application/json', ...extra } };
}

function positiveIntEnv(name, defaultValue) {
  const raw = __ENV[name];
  if (!raw) {
    return defaultValue;
  }

  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    fail(`${name} must be a positive integer, got=${raw}`);
  }

  return value;
}

function ensure(response, expectedStatus, label) {
  const ok = check(response, { [`${label} status ${expectedStatus}`]: (r) => r.status === expectedStatus });
  if (!ok) {
    fail(`${label} failed: status=${response.status} body=${response.body}`);
  }
}

function waitForBaseUrlReady() {
  const deadline = Date.now() + BOOTSTRAP_READY_TIMEOUT_MS;
  let lastStatus = 'no-response';
  let lastBody = 'null';

  while (Date.now() < deadline) {
    const response = http.get(`${BASE_URL}/actuator/health`);
    if (response.status === 200) {
      return;
    }

    lastStatus = response.status;
    lastBody = response.body;
    sleep(BOOTSTRAP_READY_INTERVAL_MS / 1000);
  }

  fail(
    `BASE_URL not ready within ${BOOTSTRAP_READY_TIMEOUT_MS}ms: baseUrl=${BASE_URL} lastStatus=${lastStatus} lastBody=${lastBody}`
  );
}

export function bootstrap() {
  waitForBaseUrlReady();

  const seed = Date.now();
  const adminExternalId = `k6-admin-${seed}`;
  const adminUser = http.post(`${BASE_URL}/users`, JSON.stringify({ externalId: adminExternalId }), jsonHeaders());
  ensure(adminUser, 201, 'create admin user');

  const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ externalId: adminExternalId }), jsonHeaders());
  ensure(login, 200, 'admin login');
  const loginBody = JSON.parse(login.body);
  const adminJwt = loginBody.accessToken;

  const project = http.post(
    `${BASE_URL}/projects`,
    JSON.stringify({ name: `k6-project-${seed}` }),
    jsonHeaders({ Authorization: `Bearer ${adminJwt}` })
  );
  ensure(project, 201, 'create project');
  const projectBody = JSON.parse(project.body);

  const benchmarkApiKey = http.post(
    `${BASE_URL}/admin/api-keys`,
    JSON.stringify({
      projectId: projectBody.id,
      rateLimitPerSec: BENCHMARK_RATE_LIMIT_PER_SEC,
      dailyQuota: BENCHMARK_DAILY_QUOTA
    }),
    jsonHeaders({ Authorization: `Bearer ${adminJwt}` })
  );
  ensure(benchmarkApiKey, 201, 'create benchmark api key');
  const benchmarkApiKeyBody = JSON.parse(benchmarkApiKey.body);

  const leaderboard = http.post(
    `${BASE_URL}/leaderboards`,
    JSON.stringify({ projectId: projectBody.id, name: `k6-board-${seed}` }),
    jsonHeaders({ Authorization: `Bearer ${adminJwt}` })
  );
  ensure(leaderboard, 201, 'create leaderboard');
  const leaderboardBody = JSON.parse(leaderboard.body);

  const userIds = [];
  for (let i = 0; i < USER_COUNT; i++) {
    const response = http.post(
      `${BASE_URL}/users`,
      JSON.stringify({ externalId: `k6-user-${seed}-${i}` }),
      jsonHeaders()
    );
    ensure(response, 201, `create event user ${i}`);
    userIds.push(String(JSON.parse(response.body).id));
  }

  return {
    baseUrl: BASE_URL,
    adminJwt,
    projectId: projectBody.id,
    leaderboardId: leaderboardBody.id,
    rawApiKey: benchmarkApiKeyBody.rawKey,
    apiKeyId: benchmarkApiKeyBody.id,
    benchmarkRateLimitPerSec: benchmarkApiKeyBody.rateLimitPerSec,
    benchmarkDailyQuota: benchmarkApiKeyBody.dailyQuota,
    defaultApiKeyId: projectBody.defaultApiKey.id,
    userIds,
    sharedIdempotencyKey: uuidv4()
  };
}

export function eventHeaders(data, idempotencyKey = uuidv4()) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.rawApiKey}`,
    'Idempotency-Key': idempotencyKey
  };
}

export function randomUserId(data) {
  const index = Math.floor(Math.random() * data.userIds.length);
  return data.userIds[index];
}

export function postEvent(data, userId, deltaScore = 1, idempotencyKey = uuidv4()) {
  return http.post(
    `${data.baseUrl}/events`,
    JSON.stringify({ leaderboardId: data.leaderboardId, userId, deltaScore }),
    { headers: eventHeaders(data, idempotencyKey) }
  );
}
