import { Counter } from 'k6/metrics';
import { check } from 'k6';
import { bootstrap, postEvent } from './lib/bootstrap.js';

const processedNewCounter = new Counter('processed_new_total');
const processedReplayCounter = new Counter('processed_replay_total');
const processedErrorCounter = new Counter('processed_error_total');

export const options = {
  scenarios: {
    same_idempotency_key: {
      executor: 'per-vu-iterations',
      vus: 50,
      iterations: 1,
      maxDuration: '30s'
    }
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    processed_error_total: ['count==0'],
    processed_new_total: ['count==1'],
    processed_replay_total: ['count==49']
  }
};

export function setup() {
  return bootstrap();
}

export default function (data) {
  const response = postEvent(data, data.userIds[0], Number(__ENV.DELTA_SCORE || 10), data.sharedIdempotencyKey);
  const ok = check(response, { 'idempotent event status is 200': (r) => r.status === 200 });
  if (!ok) {
    processedErrorCounter.add(1);
    return;
  }

  const body = JSON.parse(response.body);
  if (body.replayed) {
    processedReplayCounter.add(1);
  } else {
    processedNewCounter.add(1);
  }
}
