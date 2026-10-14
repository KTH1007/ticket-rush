// 대기열 부하 측정용. SCENARIO 환경변수로 무엇을 잴지 정한다
//   register : 열린 공연에 등록 도착률을 계단으로 올린다 (RATES, STEP_SECONDS)
//   fill     : 등록을 COUNT건 쌓는다. 메모리 측정과 폴링용 토큰 준비에 쓴다
//   preopen  : 오픈 전부터 등록을 시도한다. 오픈 전 409와 오픈 뒤 201을 모두 정상으로 본다 (RATE, DURATION)
//   poll     : TOKENS_FILE의 토큰으로 순번 조회 도착률을 계단으로 올린다 (POLL_RATES, STEP_SECONDS)
//   peak     : 50만 명이 20초 안에 몰리는 모델(0~3초 초당 50,000건, 3~10초 21,429건, 10~20초 20,000건)을 줄서기로 보낸다 (PEAK_SCALE로 규모 조절)
//   burst    : 순번 조회를 갑자기 RATE로 보낸다. 서버 지연 뒤 한꺼번에 몰리는 상황을 흉내 낸다 (RATE, DURATION)
// 쉬지 않는 VU가 아니라 도착률을 고정하는 executor를 써서 "초당 몇 건까지 버티는가"를 직접 본다
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

for (const name of ['EVENT_ID', 'SCENARIO']) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다. k6/README.md 참고`);
  }
}

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const EVENT_ID = __ENV.EVENT_ID;
const SCENARIO = __ENV.SCENARIO;
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 30);
const GAP_SECONDS = 5;
const TIMEOUT = '5s';

// nginx 상한이 거절한 503은 실패가 아니라 설계된 응답이라 따로 센다. 받아준 요청과 거절된 요청의 지연도 나눠서 본다
const busy = new Counter('busy_503');
const accepted = new Counter('accepted_count');
const acceptedMs = new Trend('accepted_ms', true);
const busyMs = new Trend('busy_ms', true);

function record(res, okStatus) {
  if (res.status === 503) {
    busy.add(1);
    busyMs.add(res.timings.duration);
  } else if (res.status === okStatus) {
    accepted.add(1);
    acceptedMs.add(res.timings.duration);
  }
}

const tokens = ['poll', 'burst'].includes(SCENARIO)
  ? new SharedArray('tokens', () => open(__ENV.TOKENS_FILE).split('\n').filter(Boolean))
  : [];

function numbers(value, fallback) {
  return (value || fallback).split(',').map(Number);
}

// 단계마다 시나리오 이름이 달라 k6 요약에 단계별 지표(submetric)가 남는다. 임계값은 항상 통과하는 값이고 지표를 내보내려는 용도다
function arrivalSteps(prefix, execName, rates) {
  const scenarios = {};
  const thresholds = {};
  rates.forEach((rate, i) => {
    const name = `${prefix}_${rate}`;
    scenarios[name] = {
      executor: 'constant-arrival-rate',
      exec: execName,
      rate,
      timeUnit: '1s',
      duration: `${STEP_SECONDS}s`,
      startTime: `${i * (STEP_SECONDS + GAP_SECONDS)}s`,
      preAllocatedVUs: Math.max(20, Math.ceil(rate / 50)),
      maxVUs: Math.max(200, Math.ceil(rate / 2)),
    };
    thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_failed{scenario:${name}}`] = ['rate>=0'];
    thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
    thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
    thresholds[`busy_503{scenario:${name}}`] = ['count>=0'];
    thresholds[`accepted_ms{scenario:${name}}`] = ['max>=0'];
    thresholds[`busy_ms{scenario:${name}}`] = ['max>=0'];
  });
  return { scenarios, thresholds };
}

function single(name, execName, rate, duration) {
  return {
    scenarios: {
      [name]: {
        executor: 'constant-arrival-rate',
        exec: execName,
        rate,
        timeUnit: '1s',
        duration,
        preAllocatedVUs: Math.max(50, Math.ceil(rate / 20)),
        maxVUs: Math.max(500, Math.ceil(rate / 2)),
      },
    },
    thresholds: {},
  };
}

function build() {
  switch (SCENARIO) {
    case 'register':
      return arrivalSteps('reg', 'register', numbers(__ENV.RATES, '500,1000,2000,4000,8000'));
    case 'fill':
      return {
        scenarios: {
          fill: {
            executor: 'shared-iterations',
            exec: 'register',
            vus: Number(__ENV.VUS || 300),
            iterations: Number(__ENV.COUNT),
            maxDuration: '30m',
          },
        },
        thresholds: {},
      };
    case 'preopen':
      return single('preopen', 'registerOrWait', Number(__ENV.RATE || 2000), __ENV.DURATION || '60s');
    case 'poll':
      return arrivalSteps('poll', 'poll', numbers(__ENV.POLL_RATES, '1000,2000,5000,10000,15000'));
    case 'peak': {
      const scale = Number(__ENV.PEAK_SCALE || 1);
      const r = (n) => Math.round(n * scale);
      return {
        scenarios: {
          peak: {
            executor: 'ramping-arrival-rate',
            exec: 'register',
            startRate: r(50000),
            timeUnit: '1s',
            preAllocatedVUs: Math.max(200, r(4000)),
            maxVUs: Math.max(500, r(12000)),
            stages: [
              { target: r(50000), duration: '3s' },
              { target: r(21429), duration: '1ms' },
              { target: r(21429), duration: '7s' },
              { target: r(20000), duration: '1ms' },
              { target: r(20000), duration: '10s' },
            ],
          },
        },
        thresholds: {},
      };
    }
    case 'burst':
      return single('burst', 'poll', Number(__ENV.RATE || 10000), __ENV.DURATION || '20s');
    default:
      throw new Error(`알 수 없는 SCENARIO: ${SCENARIO}`);
  }
}

const plan = build();

export const options = {
  scenarios: plan.scenarios,
  thresholds: plan.thresholds,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function register() {
  const res = http.post(`${BASE}/api/events/${EVENT_ID}/queues`, null, {
    timeout: TIMEOUT,
    responseCallback: http.expectedStatuses(201, 503),
  });
  record(res, 201);
  check(res, { 'registered 201 or busy 503': (r) => r.status === 201 || r.status === 503 });
}

export function registerOrWait() {
  const res = http.post(`${BASE}/api/events/${EVENT_ID}/queues`, null, {
    timeout: TIMEOUT,
    responseCallback: http.expectedStatuses(201, 409),
  });
  check(res, { '201 or 409': (r) => r.status === 201 || r.status === 409 });
}

export function poll() {
  const token = tokens[exec.scenario.iterationInTest % tokens.length];
  const res = http.get(`${BASE}/api/events/${EVENT_ID}/queues/me`, {
    headers: { 'X-Queue-Token': token },
    timeout: TIMEOUT,
    responseCallback: http.expectedStatuses(200, 503),
  });
  record(res, 200);
  check(res, { 'status 200 or busy 503': (r) => r.status === 200 || r.status === 503 });
}
