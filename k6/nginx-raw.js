// nginx 자체의 처리 한계 측정. /actuator/ 는 nginx가 앱에 보내지 않고 직접 404로 응답하므로 앱과 Redis가 개입하지 않는다
// 도착률을 단계로 올려 "입구가 초당 몇 건까지 받는가"를 본다. 부하기와 같은 머신이라 이 값은 nginx의 하한이다
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 30);
const GAP_SECONDS = 5;
const RATES = (__ENV.RATES || '10000,20000,30000').split(',').map(Number);

const busy = new Counter('busy_503');
const acceptedMs = new Trend('accepted_ms', true);
const busyMs = new Trend('busy_ms', true);

const scenarios = {};
const thresholds = {};
RATES.forEach((rate, i) => {
  const name = `raw_${rate}`;
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: `${STEP_SECONDS}s`,
    startTime: `${i * (STEP_SECONDS + GAP_SECONDS)}s`,
    preAllocatedVUs: Math.max(50, Math.ceil(rate / 50)),
    maxVUs: Math.max(500, Math.ceil(rate / 8)),
  };
  thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
  thresholds[`http_req_failed{scenario:${name}}`] = ['rate>=0'];
  thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
  thresholds[`busy_503{scenario:${name}}`] = ['count>=0'];
  thresholds[`accepted_ms{scenario:${name}}`] = ['max>=0'];
  thresholds[`busy_ms{scenario:${name}}`] = ['max>=0'];
});

export const options = { scenarios, thresholds, summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'] };

export default function () {
  const res = http.get(`${BASE}/actuator/health`, { timeout: '5s', responseCallback: http.expectedStatuses(404, 503) });
  if (res.status === 503) {
    busy.add(1);
    busyMs.add(res.timings.duration);
  } else if (res.status === 404) {
    acceptedMs.add(res.timings.duration);
  }
}
