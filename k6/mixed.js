// 활성이 된 사용자들의 혼합 부하: 홀드 -> 결제 흐름과 같은 시간의 좌석 지도 조회를 한 실행에서 정해진 속도로 보낸다
// hold.js는 홀드와 결제만 보내서, 활성 사용자가 좌석 지도를 보는 부하와 결제를 포기하는 사용자가 빠져 있다.
// 대기자의 순번 조회는 같은 시간에 queue.js를 따로 띄워 보낸다(러너가 맞춘다).
//   RATE        초당 홀드 시작 수(= 승격 속도). 한 사용자가 토큰 하나, 홀드 한 번
//   DURATION    홀드를 시작하는 시간(초). 사용자 수는 RATE x DURATION
//   TOKENS_FILE 활성 토큰을 한 줄에 하나씩 적은 파일
//   SEATS_FILE  좌석 id를 한 줄에 하나씩 적은 파일
//   THINK       홀드 뒤 결제 전까지 기다리는 초(기본 3)
//   ABANDON     결제하지 않고 떠나는 비율(기본 0.05). 홀드 만료 스위퍼의 일감이 된다
//   DWELL       활성이 된 뒤 결제하기까지 머무는 초(기본 15). 머무는 동안 좌석 상태를 조회한다
//   STATUS_POLL 좌석 상태를 조회하는 간격 초(기본 2). 좌석 상태 조회 속도 = RATE x DWELL / STATUS_POLL
//   LAYOUT      0이면 좌석 배치 첫 조회를 안 보낸다(기본은 사용자마다 한 번, 초당 RATE건)
//   STATUS      0이면 좌석 상태 조회를 안 보낸다
//   WIN_S       홀드와 결제 응답 시간을 이 초 단위 구간으로 나눠 따로 집계한다(기본 30)
//   PAY_WAIT    결제 응답을 기다리는 최대 초(기본 2). PG 장애로 3초 타임아웃까지 묶이는 실행은 5로 두어 VU를 넉넉히 미리 만든다
//   PAY_PREFIX  paymentKey 접두사. 예열과 본 측정이 같은 키를 쓰지 않게 나눈다
//   PHONE_BASE  전화번호 시작 번호
//   HOLD_TRIES  홀드가 충돌하면 다른 좌석으로 다시 시도하는 최대 횟수
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

for (const name of ['EVENT_ID', 'SEATS_FILE', 'TOKENS_FILE', 'RATE', 'DURATION']) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다. k6/README.md 참고`);
  }
}

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const EVENT_ID = __ENV.EVENT_ID;
const RATE = Number(__ENV.RATE);
const DURATION = Number(__ENV.DURATION);
const THINK = Number(__ENV.THINK || 3);
const ABANDON = Number(__ENV.ABANDON || 0.05);
const DWELL = Number(__ENV.DWELL || 15);
const STATUS_POLL = Number(__ENV.STATUS_POLL || 2);
const LAYOUT_RATE = __ENV.LAYOUT === '0' ? 0 : RATE;
const STATUS_RATE = __ENV.STATUS === '0' ? 0 : Math.round((RATE * DWELL) / STATUS_POLL);
const WIN_S = Number(__ENV.WIN_S || 30);
const HOLD_TRIES = Number(__ENV.HOLD_TRIES || 3);
const PAY_PREFIX = __ENV.PAY_PREFIX || 'pk';
const PAY_WAIT = Number(__ENV.PAY_WAIT || 2);
const TIMEOUT = '10s';

const seats = new SharedArray('seats', () => open(__ENV.SEATS_FILE).split('\n').filter(Boolean).map(Number));
const tokens = new SharedArray('tokens', () => open(__ENV.TOKENS_FILE).split('\n').filter(Boolean));

// 모자란 채로 시작하면 부하가 목표보다 낮아도 드러나지 않거나 좌석 고르기가 끝나지 않는다
if (seats.length === 0) {
  throw new Error('SEATS_FILE에 좌석이 없습니다');
}
if (tokens.length < RATE * DURATION) {
  throw new Error(`TOKENS_FILE이 모자랍니다: 필요 ${RATE * DURATION}개, 있음 ${tokens.length}개`);
}

const holdCreated = new Counter('hold_201');
const holdConflict = new Counter('hold_409');
const holdOther = new Counter('hold_other');
const holdAttempts = new Counter('hold_attempts');
const payCreated = new Counter('pay_201');
const payConflict = new Counter('pay_409');
const payUnavailable = new Counter('pay_503');
const payOther = new Counter('pay_other');
const abandoned = new Counter('abandoned');
const statusOther = new Counter('status_other');
const layoutOther = new Counter('layout_other');
const holdMs = new Trend('hold_ms', true);
const holdConflictMs = new Trend('hold_409_ms', true);
const payMs = new Trend('pay_ms', true);
const statusMs = new Trend('status_ms', true);
const layoutMs = new Trend('layout_ms', true);

function arrival(rate, durationSeconds, execName, preAllocated, maxVUs) {
  return {
    executor: 'constant-arrival-rate',
    exec: execName,
    rate,
    timeUnit: '1s',
    duration: `${durationSeconds}s`,
    preAllocatedVUs: preAllocated,
    maxVUs,
  };
}

const scenarios = {
  hold: arrival(RATE, DURATION, 'holdAndPay', Math.ceil(RATE * (THINK + PAY_WAIT)), Math.ceil(RATE * (THINK + PAY_WAIT)) * 3),
};
if (STATUS_RATE > 0) {
  scenarios.status = arrival(STATUS_RATE, DURATION, 'seatStatus', Math.ceil(STATUS_RATE * 0.2) + 50, Math.ceil(STATUS_RATE * 0.5) + 200);
}
if (LAYOUT_RATE > 0) {
  scenarios.layout = arrival(LAYOUT_RATE, DURATION, 'seatLayout', Math.ceil(LAYOUT_RATE * 0.1) + 10, Math.ceil(LAYOUT_RATE) + 100);
}

// 구간별 응답 시간과 시나리오별 못 보낸 반복을 요약에 내보내려는 임계값이다. 항상 통과하는 값이다
const thresholds = {};
for (let w = 0; w <= Math.ceil((DURATION + THINK + 5) / WIN_S); w++) {
  thresholds[`hold_ms{win:${w}}`] = ['max>=0'];
  thresholds[`pay_ms{win:${w}}`] = ['max>=0'];
}
for (const name of Object.keys(scenarios)) {
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
}

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function pickSeats() {
  const count = Math.min(Math.random() < 0.5 ? 1 : 2, seats.length);
  const picked = [];
  while (picked.length < count) {
    const id = seats[Math.floor(Math.random() * seats.length)];
    if (!picked.includes(id)) picked.push(id);
  }
  return picked;
}

function windowOf() {
  return String(Math.floor(exec.instance.currentTestRunDuration / 1000 / WIN_S));
}

export function holdAndPay() {
  const index = exec.scenario.iterationInTest;
  if (index >= tokens.length) return;
  const token = tokens[index];
  const phone = `010${String(Number(__ENV.PHONE_BASE || 0) + index).padStart(8, '0')}`;

  let hold = null;
  for (let attempt = 0; attempt < HOLD_TRIES && hold === null; attempt++) {
    holdAttempts.add(1);
    const res = http.post(
      `${BASE}/api/events/${EVENT_ID}/seats/hold`,
      JSON.stringify({ seatIds: pickSeats(), phoneNumber: phone }),
      {
        headers: { 'Content-Type': 'application/json', 'X-Queue-Token': token },
        timeout: TIMEOUT,
        responseCallback: http.expectedStatuses(201, 409),
      },
    );
    holdMs.add(res.timings.duration, { win: windowOf() });
    if (res.status === 201) {
      holdCreated.add(1);
      hold = res.json();
    } else if (res.status === 409) {
      holdConflict.add(1);
      holdConflictMs.add(res.timings.duration);
    } else {
      holdOther.add(1);
      return;
    }
  }
  if (hold === null) return;

  sleep(THINK);
  if (Math.random() < ABANDON) {
    abandoned.add(1);
    return;
  }
  const pay = http.post(
    `${BASE}/api/reservations/${hold.reservationId}/payments`,
    JSON.stringify({ holdToken: hold.holdToken, paymentKey: `${PAY_PREFIX}-${index}`, orderId: hold.orderId, amount: hold.amount }),
    {
      headers: { 'Content-Type': 'application/json' },
      timeout: TIMEOUT,
      responseCallback: http.expectedStatuses(201, 409, 503),
    },
  );
  payMs.add(pay.timings.duration, { win: windowOf() });
  if (pay.status === 201) payCreated.add(1);
  else if (pay.status === 409) payConflict.add(1);
  else if (pay.status === 503) payUnavailable.add(1);
  else payOther.add(1);
}

export function seatStatus() {
  const res = http.get(`${BASE}/api/events/${EVENT_ID}/seats/status`, { timeout: TIMEOUT, responseType: 'none' });
  statusMs.add(res.timings.duration);
  if (res.status !== 200) statusOther.add(1);
}

export function seatLayout() {
  const res = http.get(`${BASE}/api/events/${EVENT_ID}/seats/layout`, {
    headers: { 'Accept-Encoding': 'gzip' },
    timeout: TIMEOUT,
    responseType: 'none',
  });
  layoutMs.add(res.timings.duration);
  if (res.status !== 200) layoutOther.add(1);
}
