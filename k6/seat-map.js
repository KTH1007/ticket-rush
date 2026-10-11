// 좌석 지도(배치, 상태) 부하 측정용. 대상과 부하는 환경변수로 정한다
// k6는 기본으로 Accept-Encoding: gzip을 보내지 않아서, 서버의 gzip 응답 경로를 재려면 ENCODING을 명시해야 한다
import http from 'k6/http';
import { check } from 'k6';

for (const name of ['BASE', 'EVENT_ID', 'PATHNAME']) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다. k6/README.md 참고`);
  }
}

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '20s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const headers = __ENV.ENCODING ? { 'Accept-Encoding': __ENV.ENCODING } : {};
  const res = http.get(`${__ENV.BASE}/api/events/${__ENV.EVENT_ID}/${__ENV.PATHNAME}`, { headers, timeout: '30s' });
  check(res, { 'status 200': (r) => r.status === 200 });
}
