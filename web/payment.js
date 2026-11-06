import { request } from './api.js';

export function hold(eventId, token, seatIds, phoneNumber) {
  return request('POST', `/api/events/${eventId}/seats/hold`, {
    headers: { 'X-Queue-Token': token },
    body: { seatIds, phoneNumber },
  });
}

// 가짜 PG는 paymentKey를 검사하지 않는다. orderId와 amount는 홀드 응답과 같아야 한다
export function confirm(reservationId, { holdToken, orderId, amount }) {
  return request('POST', `/api/reservations/${reservationId}/payments`, {
    body: { holdToken, paymentKey: `TEST-${crypto.randomUUID()}`, orderId, amount },
  });
}

export function lookup(reservationNo, phone) {
  return request('POST', '/api/reservations/lookup', { body: { reservationNo, phone } });
}

export function cancel(reservationNo, phone) {
  return request('POST', '/api/reservations/cancel', { body: { reservationNo, phone } });
}

// 서버가 내려주는 LocalDateTime은 Asia/Seoul 기준이다
export function parseKst(localDateTime) {
  return new Date(`${localDateTime}+09:00`);
}
