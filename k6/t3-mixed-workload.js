import http from 'k6/http';
import { check } from 'k6';
import { bootstrap, postEvent, randomUserId } from './lib/bootstrap.js';

const writeRps = Number(__ENV.WRITE_RPS || 800);
const readRps = Number(__ENV.READ_RPS || 200);
const duration = __ENV.DURATION || '10m';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    write_workload: {
      executor: 'constant-arrival-rate',
      rate: writeRps,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 300,
      maxVUs: 1200,
      exec: 'writeScenario'
    },
    read_workload: {
      executor: 'constant-arrival-rate',
      rate: readRps,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 100,
      maxVUs: 400,
      exec: 'readScenario'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{scenario:write_workload}': ['p(99)<50'],
    'http_req_duration{scenario:read_workload}': ['p(99)<20']
  }
};

export function setup() {
  return bootstrap();
}

export function writeScenario(data) {
  const response = postEvent(data, randomUserId(data), Number(__ENV.DELTA_SCORE || 1));
  check(response, { 'write status is 200': (r) => r.status === 200 });
}

export function readScenario(data) {
  const pickTop = Math.random() < 0.5;
  const response = pickTop
    ? http.get(`${data.baseUrl}/leaderboards/${data.leaderboardId}/tops?offset=0&limit=10`)
    : http.get(`${data.baseUrl}/leaderboards/${data.leaderboardId}/users/${randomUserId(data)}`);

  check(response, { 'read status is 200': (r) => r.status === 200 });
}
