// 전체 사용자 흐름과 읽기 API를 목표 규모에서 함께 돌려 API별 SLO를 확인한다
//   흐름: 줄서기 -> 승격될 때까지 순번 조회 -> 좌석 홀드 -> 결제 -> 예약 조회 -> (일부) 취소
//   읽기: 공연 목록, 공연 상세, 좌석 상태. 좌석 배치는 사용자가 들어올 때 한 번 내려받는다(흐름 첫 단계)
// 환경변수
//   EVENT_ID, SEATS_FILE  공연 id, 좌석 id를 한 줄에 하나씩 적은 파일(러너가 DB에서 뽑는다)
//   FLOW_RATE   초당 새로 시작하는 사용자 수. 승격 예산(초당 100)보다 낮아야 대기 없이 승격된다
//   BROWSE_RATE 초당 읽기 요청 수
//   DURATION    부하 시간(초)
//   CANCEL_RATIO 결제한 사용자 중 취소하는 비율(0~1)
//   PHONE_BASE  전화번호 시작 번호. 예열과 본 측정이 같은 번호를 쓰지 않게 나눈다
//   TOKENS_FILE 승격된 대기 토큰을 한 줄에 하나씩 적은 파일. 있으면 사용자가 줄서기와 순번 조회를 건너뛰고 이 토큰으로 홀드부터 시작한다
//               (대기자 수십만 명 뒤에서는 새 사용자가 승격되지 못하므로, 줄 앞에서 승격되는 사용자만 흉내 낸다)
// 좋은 응답과 의도된 거절을 나눠 센다. 의도된 거절(오픈 전, 대기열 가득, 좌석 충돌, PG 거절 등)은 실패로 세지 않는다
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

for (const name of ['EVENT_ID', 'SEATS_FILE']) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다. k6/README.md 참고`);
  }
}

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const EVENT_ID = __ENV.EVENT_ID;
const FLOW_RATE = Number(__ENV.FLOW_RATE || 50);
const BROWSE_RATE = Number(__ENV.BROWSE_RATE || 200);
const DURATION = Number(__ENV.DURATION || 30);
const CANCEL_RATIO = Number(__ENV.CANCEL_RATIO || 0.1);
const THINK = Number(__ENV.THINK || 1);
const HOLD_TRIES = Number(__ENV.HOLD_TRIES || 3);
const WAIT_LIMIT_SECONDS = Number(__ENV.WAIT_LIMIT || 60);
const PHONE_BASE = Number(__ENV.PHONE_BASE || 0);
const TIMEOUT = '10s';

const seats = new SharedArray('seats', () => open(__ENV.SEATS_FILE).split('\n').filter(Boolean).map(Number));
const TOKENS_MODE = Boolean(__ENV.TOKENS_FILE);
const tokens = new SharedArray('tokens', () => (TOKENS_MODE ? open(__ENV.TOKENS_FILE).split('\n').filter(Boolean) : []));

// SLO(docs/slo.md)와 같은 값. 어기면 k6가 임계값 실패로 끝난다
const SLO = {
  register: { p95: 200, p99: 500, good: 0.999 },
  poll: { p95: 100, p99: 300, good: 0.999 },
  events_list: { p95: 200, p99: 500, good: 0.999 },
  events_detail: { p95: 200, p99: 500, good: 0.999 },
  seat_status: { p95: 100, p99: 200, good: 0.999 },
  seat_layout: { p95: 150, p99: 300, good: 0.999 },
  hold: { p95: 300, p99: 1000, good: 0.999 },
  pay: { p95: 1000, p99: 3000, good: 0.995 },
  lookup: { p95: 200, p99: 500, good: 0.999 },
  cancel: { p95: 1000, p99: 3000, good: 0.995 },
};

const good = new Rate('sli_good');
const excluded = new Counter('sli_excluded');
const flowDone = new Counter('flow_completed');
const flowCanceled = new Counter('flow_canceled');
const flowAbandoned = new Counter('flow_abandoned');

function thresholds() {
  const result = {};
  for (const [api, slo] of Object.entries(SLO)) {
    // 토큰을 받아 시작하면 줄서기와 순번 조회는 이 시나리오에서 하지 않는다(별도 부하가 잰다)
    if (TOKENS_MODE && (api === 'register' || api === 'poll')) continue;
    result[`http_req_duration{api:${api}}`] = [`p(95)<${slo.p95}`, `p(99)<${slo.p99}`];
    result[`sli_good{api:${api}}`] = [`rate>=${slo.good}`];
    result[`sli_excluded{api:${api}}`] = ['count>=0'];
    // 판정에는 쓰지 않고 시간이 어디에 쓰였는지(서버 응답 시작 대 받는 시간) 보려고 내보낸다
    result[`http_req_waiting{api:${api}}`] = ['max>=0'];
    result[`http_req_receiving{api:${api}}`] = ['max>=0'];
  }
  return result;
}

export const options = {
  scenarios: {
    flow: {
      executor: 'constant-arrival-rate',
      exec: 'flow',
      rate: FLOW_RATE,
      timeUnit: '1s',
      duration: `${DURATION}s`,
      preAllocatedVUs: Math.max(50, FLOW_RATE * 20),
      maxVUs: Math.max(200, FLOW_RATE * 40),
    },
    browse: {
      executor: 'constant-arrival-rate',
      exec: 'browse',
      rate: BROWSE_RATE,
      timeUnit: '1s',
      duration: `${DURATION}s`,
      preAllocatedVUs: Math.max(20, Math.ceil(BROWSE_RATE / 20)),
      maxVUs: Math.max(100, Math.ceil(BROWSE_RATE / 2)),
    },
  },
  thresholds: thresholds(),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// 좋은 응답은 good, 의도된 거절은 제외, 나머지는 실패로 센다
function sli(api, res, goodStatuses, excludedStatuses) {
  if (goodStatuses.includes(res.status)) {
    good.add(true, { api });
  } else if (excludedStatuses.includes(res.status) && !isServerFault(api, res)) {
    excluded.add(1, { api });
  } else {
    good.add(false, { api });
  }
}

// 503은 대기열이 가득 찬 의도된 거절(QUEUE_FULL)만 제외한다. 그 밖의 503은 장애다
function isServerFault(api, res) {
  if (res.status < 500) return false;
  if (api === 'register' && res.status === 503) {
    try {
      return res.json('code') !== 'QUEUE_FULL';
    } catch (e) {
      return true;
    }
  }
  return true;
}

function req(api, method, path, body, extraHeaders) {
  const params = {
    tags: { api },
    timeout: TIMEOUT,
    headers: Object.assign({}, body ? { 'Content-Type': 'application/json' } : {}, extraHeaders || {}),
    responseCallback: http.expectedStatuses({ min: 200, max: 599 }),
  };
  // 좌석 배치는 압축본이 250KB(풀면 2.5MB)라 부하기가 본문을 풀면서 부하기 쪽이 병목이 된다(같은 서버가 TTFB 65ms인데 총 1.5초로 보임).
  // 본문을 버리고 서버 응답과 전송 시간만 잰다. 사용자 쪽 다운로드 시간은 회선에 달려 SLO에 넣지 않는다
  if (api === 'seat_layout') params.responseType = 'none';
  const url = `${BASE}${path}`;
  return method === 'GET' ? http.get(url, params) : http.post(url, body ? JSON.stringify(body) : null, params);
}

function pickSeats() {
  const count = Math.random() < 0.5 ? 1 : 2;
  const picked = [];
  while (picked.length < count) {
    const id = seats[Math.floor(Math.random() * seats.length)];
    if (!picked.includes(id)) picked.push(id);
  }
  return picked;
}

function retryAfterSeconds(res) {
  const value = Number(res.headers['Retry-After']);
  return value > 0 ? value : 2;
}

function register() {
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = req('register', 'POST', `/api/events/${EVENT_ID}/queues`);
    sli('register', res, [201], [409, 429, 503]);
    if (res.status === 201) return res.headers['X-Queue-Token'];
    if (res.status !== 503) return null;
    sleep(retryAfterSeconds(res));
  }
  return null;
}

function waitUntilActive(token) {
  const started = Date.now();
  while ((Date.now() - started) / 1000 < WAIT_LIMIT_SECONDS) {
    const res = req('poll', 'GET', `/api/events/${EVENT_ID}/queues/me`, null, { 'X-Queue-Token': token });
    sli('poll', res, [200], [401]);
    if (res.status === 200) {
      const body = res.json();
      if (body.rank === 0) return true;
      sleep(Math.max(body.nextPollIntervalMs, 500) / 1000);
    } else {
      sleep(retryAfterSeconds(res));
    }
  }
  return false;
}

function holdSeats(token, phone) {
  for (let attempt = 0; attempt < HOLD_TRIES; attempt++) {
    const res = req('hold', 'POST', `/api/events/${EVENT_ID}/seats/hold`, { seatIds: pickSeats(), phoneNumber: phone }, { 'X-Queue-Token': token });
    sli('hold', res, [201], [403, 409]);
    if (res.status === 201) return res.json();
    if (res.status !== 409) return null;
  }
  return null;
}

export function flow() {
  const phone = `010${String(PHONE_BASE + exec.scenario.iterationInTest).padStart(8, '0')}`;
  // 새 사용자는 좌석 배치를 한 번 내려받는다. 이후에는 ETag와 Cache-Control로 클라이언트가 캐시하므로 반복하지 않는다
  const layout = req('seat_layout', 'GET', `/api/events/${EVENT_ID}/seats/layout`);
  sli('seat_layout', layout, [200], []);
  const token = TOKENS_MODE ? tokens[exec.scenario.iterationInTest] : register();
  if (token === undefined || token === null || (!TOKENS_MODE && !waitUntilActive(token))) {
    flowAbandoned.add(1);
    return;
  }
  const hold = holdSeats(token, phone);
  if (hold === null) {
    flowAbandoned.add(1);
    return;
  }
  sleep(THINK);
  const paid = req('pay', 'POST', `/api/reservations/${hold.reservationId}/payments`, {
    holdToken: hold.holdToken,
    paymentKey: `pk-slo-${PHONE_BASE}-${exec.scenario.iterationInTest}`,
    orderId: hold.orderId,
    amount: hold.amount,
  });
  sli('pay', paid, [201], [409]);
  if (paid.status !== 201) {
    flowAbandoned.add(1);
    return;
  }
  const reservationNo = paid.json('reservationNo');
  const lookup = req('lookup', 'POST', '/api/reservations/lookup', { reservationNo, phone });
  sli('lookup', lookup, [200], [404, 429]);
  check(lookup, { 'lookup 200': (r) => r.status === 200 });
  flowDone.add(1);
  if (Math.random() < CANCEL_RATIO) {
    const canceled = req('cancel', 'POST', '/api/reservations/cancel', { reservationNo, phone });
    sli('cancel', canceled, [200], [409]);
    if (canceled.status === 200) flowCanceled.add(1);
  }
}

const READS = [
  { api: 'events_list', path: () => '/api/events', weight: 10 },
  { api: 'events_detail', path: () => `/api/events/${EVENT_ID}`, weight: 20 },
  { api: 'seat_status', path: () => `/api/events/${EVENT_ID}/seats/status`, weight: 70 },
];

export function browse() {
  let roll = Math.random() * 100;
  for (const read of READS) {
    roll -= read.weight;
    if (roll <= 0) {
      const res = req(read.api, 'GET', read.path());
      sli(read.api, res, [200], []);
      return;
    }
  }
}
