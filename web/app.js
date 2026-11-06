import { onLog, request } from './api.js';
import { register, startPolling } from './queue.js';
import { SeatMap } from './seatmap.js';
import * as payment from './payment.js';

const STATUS_REFRESH_MS = 2000;
const MAX_LOG_ROWS = 300;

const $ = (id) => document.getElementById(id);

const state = {
  eventId: null,
  token: null,
  active: false,
  poller: null,
  hold: null,
  paid: false,
  countdown: null,
  statusTimer: null,
};

const seatMap = new SeatMap($('seat-canvas'), {
  onChange: () => render(),
  onHover: (text) => {
    $('seat-info').textContent = text;
  },
  onMessage: (text) => say('hold-msg', text, 'warn'),
});

function say(id, text, kind = '') {
  const element = $(id);
  element.textContent = text;
  element.className = `msg ${kind}`.trim();
}

function describe(response) {
  const data = response.data || {};
  const parts = [response.status === 0 ? '네트워크 오류' : response.status];
  if (data.code) parts.push(data.code);
  if (data.detail) parts.push(data.detail);
  if (response.retryAfter !== null && response.retryAfter !== undefined) parts.push(`Retry-After ${response.retryAfter}초`);
  if (response.error) parts.push(response.error);
  return parts.join(' / ');
}

function randomPhone() {
  return `010${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`;
}

function formatTime(date) {
  return `${date.toTimeString().slice(0, 8)}.${String(date.getMilliseconds()).padStart(3, '0')}`;
}

const logBody = document.querySelector('#log tbody');

function addCell(row, text, className = '') {
  const cell = row.insertCell();
  cell.textContent = text;
  if (className) cell.className = className;
}

onLog((entry) => {
  const kind = entry.status === 0 || entry.status >= 500 ? 'bad' : entry.status >= 400 ? 'warn' : 'ok';
  const notes = [];
  if (entry.retryAfter !== null) notes.push(`Retry-After ${entry.retryAfter}`);
  if (entry.cacheStatus) notes.push(`cache ${entry.cacheStatus}`);
  if (entry.code) notes.push(entry.code);
  if (entry.requestId) notes.push(`id ${entry.requestId.slice(0, 8)}`);
  if (entry.error) notes.push(entry.error);

  const row = logBody.insertRow(0);
  addCell(row, formatTime(entry.at), 'num');
  addCell(row, `${entry.method} ${entry.path}`);
  addCell(row, entry.status === 0 ? 'ERR' : String(entry.status), kind);
  addCell(row, String(entry.ms), 'num');
  addCell(row, notes.join(', '));
  while (logBody.rows.length > MAX_LOG_ROWS) logBody.deleteRow(-1);
});

function stopPolling() {
  if (state.poller) state.poller.stop();
  state.poller = null;
}

function stopCountdown() {
  clearInterval(state.countdown);
  state.countdown = null;
}

function stopStatusRefresh() {
  clearInterval(state.statusTimer);
  state.statusTimer = null;
}

function resetFlow() {
  stopPolling();
  stopCountdown();
  Object.assign(state, { token: null, active: false, hold: null, paid: false });
  $('queue-token').textContent = '-';
  $('queue-status').textContent = '순번 정보 없음';
  $('hold-info').textContent = '홀드 정보 없음';
  for (const id of ['queue-msg', 'hold-msg', 'pay-msg', 'lookup-msg']) say(id, '');
}

function renderLegend() {
  const legend = $('seat-legend');
  legend.replaceChildren();
  for (const { label, color } of seatMap.legend()) {
    const item = document.createElement('span');
    item.style.setProperty('--c', color);
    item.textContent = label;
    legend.append(item);
  }
}

function renderHoldInfo(secondsLeft) {
  const { reservationId, orderId, amount } = state.hold;
  const tail = state.paid ? '결제 완료' : `남은 시간 ${secondsLeft}초`;
  $('hold-info').textContent = `예약 ${reservationId} / 주문번호 ${orderId} / 금액 ${amount}원 / ${tail}`;
}

function render() {
  const ready = seatMap.seats.length > 0;
  $('btn-register').disabled = state.eventId === null;
  $('btn-auto').disabled = !ready;
  $('btn-clear').disabled = seatMap.selectedCount() === 0;
  $('seat-selected').textContent = seatMap.describeSelection();
  $('btn-hold').disabled = !(state.token && state.active && seatMap.selectedCount() > 0 && !state.hold);
  $('btn-pay').disabled = !(state.hold && !state.paid);
}

async function loadEvents() {
  const response = await request('GET', '/api/events?size=50');
  if (!response.ok) {
    say('event-info', describe(response), 'bad');
    return;
  }
  const select = $('event-select');
  select.replaceChildren();
  for (const event of response.data.content) {
    const option = document.createElement('option');
    option.value = String(event.id);
    option.textContent = `${event.id}번 ${event.title} (오픈 ${event.opensAt}, 시작 ${event.startsAt})`;
    select.append(option);
  }
  if (response.data.content.length === 0) {
    say('event-info', '공연이 없습니다. scripts/seed-event.sql로 시드를 넣어 주세요', 'warn');
    return;
  }
  await selectEvent(Number(select.value));
}

let selectSeq = 0;

async function selectEvent(eventId) {
  const seq = ++selectSeq;
  resetFlow();
  stopStatusRefresh();
  state.eventId = eventId;
  say('event-info', '좌석 배치를 불러오는 중입니다');
  const response = await seatMap.load(eventId);
  if (seq !== selectSeq) return;
  if (!response.ok) {
    say('event-info', describe(response), 'bad');
    render();
    return;
  }
  renderLegend();
  say('event-info', `좌석 ${seatMap.seats.length}석을 불러왔습니다`, 'ok');
  await refreshStatus();
  if (seq !== selectSeq) return;
  state.statusTimer = setInterval(refreshStatus, STATUS_REFRESH_MS);
  render();
}

async function refreshStatus() {
  const eventId = state.eventId;
  if (eventId === null) return;
  const response = await request('GET', `/api/events/${eventId}/seats/status`);
  if (eventId !== state.eventId) return;
  const summary = $('seat-summary');
  if (!response.ok) {
    summary.textContent = `상태 조회 실패: ${describe(response)}`;
    return;
  }
  const result = seatMap.applyStatus(response.data, $('opt-stale-guard').checked);
  if (result.error) {
    summary.textContent = result.error;
  } else if (result.stale) {
    summary.textContent = `더 오래된 스냅샷을 무시했습니다(builtAt ${result.builtAt.toISOString()})`;
  } else {
    const [available, held, sold] = result.counts;
    const age = Math.round((Date.now() - result.builtAt.getTime()) / 1000);
    summary.textContent = `선택 가능 ${available} / 홀드 ${held} / 판매 ${sold} (스냅샷 나이 ${age}초)`;
  }
}

function beginPolling() {
  state.poller = startPolling(
    state.eventId,
    state.token,
    {
      followInterval: () => $('opt-interval').checked,
      followRetryAfter: () => $('opt-retry-after').checked,
    },
    {
      onResponse(response) {
        if (response.status === 200) {
          const { rank, nextPollIntervalMs, soldOut } = response.data;
          $('queue-status').textContent =
            `${response.polls}번째 조회 / 순번 ${rank} / 다음 조회까지 ${nextPollIntervalMs}ms / 매진 ${soldOut ? '예' : '아니오'}`;
        } else {
          say('queue-msg', `${response.polls}번째 조회 실패: ${describe(response)}`, 'warn');
        }
      },
      onActive() {
        state.active = true;
        say('queue-msg', '승격되었습니다. 좌석을 선택해 홀드할 수 있습니다', 'ok');
        render();
      },
      onInvalid(response) {
        state.token = null;
        $('queue-token').textContent = '-';
        say('queue-msg', `토큰이 유효하지 않습니다: ${describe(response)}`, 'bad');
        render();
      },
    },
  );
}

function startCountdown() {
  stopCountdown();
  const expiresAt = payment.parseKst(state.hold.holdExpiresAt).getTime();
  const tick = () => {
    const left = Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
    renderHoldInfo(left);
    if (left === 0) {
      stopCountdown();
      say('pay-msg', '홀드가 만료되었습니다. 결제하면 거절될 수 있습니다', 'warn');
    }
  };
  tick();
  state.countdown = setInterval(tick, 1000);
}

$('event-select').addEventListener('change', (event) => selectEvent(Number(event.target.value)));
$('event-reload').addEventListener('click', loadEvents);
$('btn-reset').addEventListener('click', () => {
  $('phone').value = randomPhone();
  if (state.eventId !== null) selectEvent(state.eventId);
});

$('btn-register').addEventListener('click', async () => {
  stopPolling();
  Object.assign(state, { token: null, active: false });
  const response = await register(state.eventId);
  if (response.status !== 201 || !response.token) {
    say('queue-msg', describe(response), 'bad');
    render();
    return;
  }
  state.token = response.token;
  $('queue-token').textContent = `${response.token.slice(0, 8)}...`;
  say('queue-msg', '줄서기에 성공했습니다. 순번을 조회합니다', 'ok');
  beginPolling();
  render();
});

$('btn-auto').addEventListener('click', () => seatMap.autoSelect());
$('btn-clear').addEventListener('click', () => seatMap.clearSelection());
$('btn-phone-random').addEventListener('click', () => {
  $('phone').value = randomPhone();
});

$('btn-hold').addEventListener('click', async () => {
  const response = await payment.hold(state.eventId, state.token, seatMap.selectedIds(), $('phone').value.trim());
  if (response.status !== 201) {
    say('hold-msg', describe(response), 'bad');
    if (response.status === 409) refreshStatus();
    return;
  }
  state.hold = response.data;
  state.paid = false;
  seatMap.markSelectedAsMine();
  say('hold-msg', `홀드에 성공했습니다(예약 ${state.hold.reservationId})`, 'ok');
  startCountdown();
  render();
});

$('btn-pay').addEventListener('click', async () => {
  const response = await payment.confirm(state.hold.reservationId, state.hold);
  if (response.status !== 201) {
    say('pay-msg', describe(response), 'bad');
    return;
  }
  state.paid = true;
  stopCountdown();
  renderHoldInfo(0);
  say('pay-msg', `결제가 완료되었습니다. 예매번호 ${response.data.reservationNo}`, 'ok');
  $('lookup-no').value = response.data.reservationNo;
  $('lookup-phone').value = $('phone').value.trim();
  refreshStatus();
  render();
});

async function lookupOrCancel(action) {
  const response = await action($('lookup-no').value.trim(), $('lookup-phone').value.trim());
  $('lookup-result').textContent = response.data ? JSON.stringify(response.data, null, 2) : '';
  say('lookup-msg', response.ok ? '요청에 성공했습니다' : describe(response), response.ok ? 'ok' : 'bad');
  if (response.ok) refreshStatus();
}

$('btn-lookup').addEventListener('click', () => lookupOrCancel(payment.lookup));
$('btn-cancel').addEventListener('click', () => lookupOrCancel(payment.cancel));
$('log-clear').addEventListener('click', () => logBody.replaceChildren());

$('phone').value = randomPhone();
loadEvents();
