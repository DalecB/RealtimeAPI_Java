import { check } from 'k6';
import { bootstrap, postEvent, randomUserId } from './lib/bootstrap.js';

const rate = Number(__ENV.WRITE_RPS || 1000);
const duration = __ENV.DURATION || '5m';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    hot_path_write_fixed: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 300,
      maxVUs: 1500
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
