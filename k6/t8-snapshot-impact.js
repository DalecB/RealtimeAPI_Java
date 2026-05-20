import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { bootstrap, postEvent, randomUserId } from './lib/bootstrap.js';

const writeRps = Number(__ENV.WRITE_RPS || 800);
const readRps = Number(__ENV.READ_RPS || 200);
const duration = __ENV.DURATION || '10m';
const writeDurationMs = new Trend('write_duration_ms');
const readDurationMs = new Trend('read_duration_ms');
const writeNon200Rate = new Rate('write_non_200_rate');
const readNon200Rate = new Rate('read_non_200_rate');

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
    write_duration_ms: ['p(99)<50'],
    read_duration_ms: ['p(99)<20'],
    write_non_200_rate: ['rate==0'],
    read_non_200_rate: ['rate<0.001']
  }
};

export function setup() {
  return bootstrap();
}

export function writeScenario(data) {
  const response = postEvent(data, randomUserId(data), Number(__ENV.DELTA_SCORE || 1));
  writeDurationMs.add(response.timings.duration);
  writeNon200Rate.add(response.status !== 200);
  check(response, { 'write status is 200': (r) => r.status === 200 });
}

export function readScenario(data) {
  const response = http.get(`${data.baseUrl}/leaderboards/${data.leaderboardId}/tops?offset=0&limit=10`);
  readDurationMs.add(response.timings.duration);
  readNon200Rate.add(response.status !== 200);
  check(response, { 'read status is 200': (r) => r.status === 200 });
}
