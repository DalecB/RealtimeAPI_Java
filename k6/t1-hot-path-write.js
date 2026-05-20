import { check } from 'k6';
import { bootstrap, postEvent, randomUserId } from './lib/bootstrap.js';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    hot_path_write: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 1500,
      stages: [
        { target: 100, duration: '2m' },
        { target: 500, duration: '2m' },
        { target: 1000, duration: '2m' },
        { target: 1500, duration: '2m' }
      ]
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    http_req_duration: ['p(99)<50']
  }
};

export function setup() {
  return bootstrap();
}

export default function (data) {
  const response = postEvent(data, randomUserId(data), Number(__ENV.DELTA_SCORE || 1));
  check(response, {
    'event status is 200': (r) => r.status === 200
  });
}
