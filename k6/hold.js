// 활성이 된 토큰으로 홀드 -> 결제를 정해진 속도로 보낸다 (DB가 홀드 도착 속도를 얼마나 버티는지 측정)
// journey.js는 순번 조회 간격(15초) 때문에 홀드가 승격 속도가 아니라 줄서기 도착 속도로 몰려서, 홀드 도착 속도를 직접 정할 수 없다.
// 여기서는 러너가 미리 활성으로 만든 토큰을 써서 초당 몇 건이 DB에 닿는지를 정확히 정한다.
//   MODE        rate: 초당 RATE건씩 DURATION 동안(constant-arrival-rate), burst: USERS명이 SPREAD초 안에 한꺼번에(대기열이 없을 때를 흉내)
//   TOKENS_FILE 활성 토큰을 한 줄에 하나씩 적은 파일(사용자 한 명이 토큰 하나)
//   SEATS_FILE  좌석 id를 한 줄에 하나씩 적은 파일
//   THINK       홀드 뒤 결제 전까지 기다리는 초
//   SEAT_POOL   좌석 id 앞쪽 N개에서만 고른다. 작을수록 같은 좌석에 몰려 충돌이 늘어난다
//   HOLD_TRIES  홀드가 충돌하면 다른 좌석으로 다시 시도하는 최대 횟수
//   PHONE_BASE  전화번호 시작 번호(예열과 본 측정이 1인당 좌석 제한에 걸리지 않게 나눈다)
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

for (const name of ['EVENT_ID', 'SEATS_FILE', 'TOKENS_FILE', 'MODE']) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다. k6/README.md 참고`);
  }
}

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const EVENT_ID = __ENV.EVENT_ID;
const MODE = __ENV.MODE;
const THINK = Number(__ENV.THINK || 3);
const HOLD_TRIES = Number(__ENV.HOLD_TRIES || 3);
const TIMEOUT = '10s';

const allSeats = new SharedArray('seats', () => open(__ENV.SEATS_FILE).split('\n').filter(Boolean).map(Number));
const seats = allSeats.slice(0, Number(__ENV.SEAT_POOL || allSeats.length));
const tokens = new SharedArray('tokens', () => open(__ENV.TOKENS_FILE).split('\n').filter(Boolean));

// summarize-db.py가 journey.js와 같은 이름을 읽으므로 이름을 맞춘다
const registered = new Counter('journey_registered');
const activated = new Counter('journey_activated');
const holdAttempts = new Counter('journey_hold_attempts');
const holdCreated = new Counter('journey_hold_201');
const holdConflict = new Counter('journey_hold_conflict');
const holdOther = new Counter('journey_hold_other');
const paid = new Counter('journey_paid_201');
const payOther = new Counter('journey_pay_other');
const queueWait = new Trend('journey_queue_wait_s', true);
const holdMs = new Trend('journey_hold_ms', true);
const holdConflictMs = new Trend('journey_hold_conflict_ms', true);
const payMs = new Trend('journey_pay_ms', true);

const scenarios = {
  rate: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 100),
    timeUnit: '1s',
    duration: `${Number(__ENV.DURATION || 30)}s`,
    preAllocatedVUs: Math.ceil(Number(__ENV.RATE || 100) * (THINK + 2)),
    maxVUs: Math.ceil(Number(__ENV.RATE || 100) * (THINK + 2)) * 4,
  },
  burst: {
    executor: 'per-vu-iterations',
    vus: Number(__ENV.USERS || 3000),
    iterations: 1,
    maxDuration: '300s',
  },
};

export const options = {
  scenarios: { hold: scenarios[MODE] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function pickSeats() {
  const count = Math.random() < 0.5 ? 1 : 2;
  const picked = [];
  while (picked.length < count) {
    const id = seats[Math.floor(Math.random() * seats.length)];
    if (!picked.includes(id)) picked.push(id);
  }
  return picked;
}

export default function () {
  // 사용자 한 명이 토큰 하나. 도착률 모드는 시작한 반복 번호, 한꺼번에 모드는 VU 번호를 쓴다
  const index = MODE === 'rate' ? exec.scenario.iterationInTest : exec.vu.idInTest - 1;
  if (index >= tokens.length) return;
  const token = tokens[index];
  const phone = `010${String(Number(__ENV.PHONE_BASE || 0) + index).padStart(8, '0')}`;

  if (MODE === 'burst') sleep(Math.random() * Number(__ENV.SPREAD || 2));
  registered.add(1);
  activated.add(1);
  queueWait.add(0);

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
    holdMs.add(res.timings.duration);
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
    JSON.stringify({ holdToken: hold.holdToken, paymentKey: `pk-${__ENV.PHONE_BASE || 0}-${index}`, orderId: hold.orderId, amount: hold.amount }),
    { headers: { 'Content-Type': 'application/json' }, timeout: TIMEOUT },
  );
  payMs.add(pay.timings.duration);
  if (pay.status === 201) {
    paid.add(1);
  } else {
    payOther.add(1);
  }
  check(pay, { 'payment 201': (r) => r.status === 201 });
}
