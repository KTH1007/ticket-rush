// 줄서기 -> 활성이 될 때까지 순번 조회 -> 좌석 홀드 -> 결제까지 한 사용자 흐름 (대기열이 DB를 지키는지 측정)
// 사용자 한 명이 VU 한 개다. 승격 속도(초당 100명 등)가 정한 속도로 홀드와 결제가 도착하는 것을 그대로 본다
//   USERS      사용자 수(= VU 수)
//   SEATS_FILE 좌석 id를 한 줄에 하나씩 적은 파일(러너가 DB에서 뽑는다)
//   SEAT_POOL  좌석 id 앞쪽 N개에서만 고른다. 작을수록 같은 좌석에 몰려 홀드 충돌이 늘어난다
//   THINK      홀드 뒤 결제 전까지 기다리는 초
//   HOLD_TRIES 홀드가 충돌하면 다른 좌석으로 다시 시도하는 최대 횟수
//   REG_RETRIES 줄서기가 503이면 Retry-After와 지터로 다시 시도하는 최대 횟수(기본 30)
//   REG_SPREAD 줄서기를 이 초 동안 무작위로 퍼뜨린다. 수천 VU가 같은 순간에 접속하면 부하기와 nginx 사이에서 연결이 끊긴다(기본 10)
//   PHONE_BASE 전화번호 시작 번호. 예열과 본 측정이 같은 번호를 써서 1인당 좌석 제한에 걸리지 않게 나눈다
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

for (const name of ['EVENT_ID', 'SEATS_FILE', 'USERS']) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다. k6/README.md 참고`);
  }
}

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const EVENT_ID = __ENV.EVENT_ID;
const THINK = Number(__ENV.THINK || 3);
const HOLD_TRIES = Number(__ENV.HOLD_TRIES || 3);
const REG_RETRIES = Number(__ENV.REG_RETRIES || 30);
const WAIT_LIMIT_SECONDS = Number(__ENV.WAIT_LIMIT || 600);
const TIMEOUT = '10s';

const allSeats = new SharedArray('seats', () => open(__ENV.SEATS_FILE).split('\n').filter(Boolean).map(Number));
const seats = allSeats.slice(0, Number(__ENV.SEAT_POOL || allSeats.length));

const registered = new Counter('journey_registered');
const activated = new Counter('journey_activated');
const holdAttempts = new Counter('journey_hold_attempts');
const holdCreated = new Counter('journey_hold_201');
const holdConflict = new Counter('journey_hold_conflict');
const holdOther = new Counter('journey_hold_other');
const paid = new Counter('journey_paid_201');
const payOther = new Counter('journey_pay_other');
const queueWait = new Trend('journey_queue_wait_s', true);
const registerBusy = new Counter('journey_register_503');
const registerWait = new Trend('journey_register_wait_s', true);
const registerTries = new Trend('journey_register_tries');
const pollBusy = new Counter('journey_poll_503');
const holdBusy = new Counter('journey_hold_503');
const payBusy = new Counter('journey_pay_503');
const holdMs = new Trend('journey_hold_ms', true);
const holdConflictMs = new Trend('journey_hold_conflict_ms', true);
const payMs = new Trend('journey_pay_ms', true);

export const options = {
  scenarios: {
    journey: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.USERS),
      iterations: 1,
      maxDuration: `${WAIT_LIMIT_SECONDS + 120}s`,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function retryAfterSeconds(res) {
  const value = Number(res.headers['Retry-After']);
  return value > 0 ? value : 3;
}

// 처음 누른 시점부터 순번을 받을 때까지. 503이면 서버가 알려준 간격에 지터를 더해 다시 시도한다
function registerWithRetry() {
  const clickedAt = Date.now();
  for (let attempt = 1; attempt <= REG_RETRIES; attempt++) {
    const res = http.post(`${BASE}/api/events/${EVENT_ID}/queues`, null, {
      timeout: TIMEOUT,
      responseCallback: http.expectedStatuses(201, 503),
    });
    if (res.status === 201) {
      registerWait.add((Date.now() - clickedAt) / 1000);
      registerTries.add(attempt);
      return res;
    }
    if (res.status !== 503) return null;
    registerBusy.add(1);
    sleep(retryAfterSeconds(res) + Math.random() * 2);
  }
  return null;
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

// 서버가 알려준 간격을 그대로 따른다. 지터는 서버가 안 주므로 클라이언트가 조금 더한다
function waitUntilActive(token) {
  const started = Date.now();
  while ((Date.now() - started) / 1000 < WAIT_LIMIT_SECONDS) {
    const res = http.get(`${BASE}/api/events/${EVENT_ID}/queues/me`, {
      headers: { 'X-Queue-Token': token },
      timeout: TIMEOUT,
      responseCallback: http.expectedStatuses(200, 503),
    });
    if (res.status === 200) {
      const body = res.json();
      if (body.rank === 0) return (Date.now() - started) / 1000;
      sleep(Math.max(body.nextPollIntervalMs, 1000) / 1000 * (0.9 + Math.random() * 0.2));
    } else {
      if (res.status === 503) pollBusy.add(1);
      sleep(retryAfterSeconds(res) + Math.random() * 2);
    }
  }
  return null;
}

export default function () {
  sleep(Math.random() * Number(__ENV.REG_SPREAD || 10));
  const reg = registerWithRetry();
  if (reg === null) return;
  registered.add(1);
  const registeredAt = Date.now();
  const token = reg.headers['X-Queue-Token'];

  if (waitUntilActive(token) === null) return;
  queueWait.add((Date.now() - registeredAt) / 1000);
  activated.add(1);

  const phone = `010${String(Number(__ENV.PHONE_BASE || 0) + exec.vu.idInTest).padStart(8, '0')}`;
  let hold = null;
  for (let attempt = 0; attempt < HOLD_TRIES && hold === null; attempt++) {
    holdAttempts.add(1);
    const res = http.post(
      `${BASE}/api/events/${EVENT_ID}/seats/hold`,
      JSON.stringify({ seatIds: pickSeats(), phoneNumber: phone }),
      {
        headers: { 'Content-Type': 'application/json', 'X-Queue-Token': token },
        timeout: TIMEOUT,
        responseCallback: http.expectedStatuses(201, 409, 503),
      },
    );
    holdMs.add(res.timings.duration);
    if (res.status === 503) {
      holdBusy.add(1);
      return;
    }
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
  const pay = http.post(
    `${BASE}/api/reservations/${hold.reservationId}/payments`,
    JSON.stringify({ holdToken: hold.holdToken, paymentKey: `pk-${exec.vu.idInTest}`, orderId: hold.orderId, amount: hold.amount }),
    { headers: { 'Content-Type': 'application/json' }, timeout: TIMEOUT, responseCallback: http.expectedStatuses(201, 503) },
  );
  payMs.add(pay.timings.duration);
  if (pay.status === 503) payBusy.add(1);
  if (pay.status === 201) {
    paid.add(1);
  } else {
    payOther.add(1);
  }
  check(pay, { 'payment 201': (r) => r.status === 201 });
}
