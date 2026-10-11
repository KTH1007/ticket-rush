# k6 부하 측정

## 좌석 지도 (`seat-map.js`)

`GET /api/events/{id}/seats/layout`과 `seats/status`를 반복 호출한다. 이슈 #127의 측정에 쓴 스크립트다.

```bash
k6 run --summary-export=result.json \
  -e BASE=http://127.0.0.1:18082 -e EVENT_ID=1 \
  -e PATHNAME=seats/status -e VUS=50 -e DURATION=20s \
  k6/seat-map.js

# 배치는 gzip을 명시해야 서버의 gzip 응답 경로를 잰다. ENCODING=identity면 서버가 풀어서 주는 예외 경로를 잰다
k6 run -e BASE=... -e EVENT_ID=1 -e PATHNAME=seats/layout -e ENCODING=gzip -e VUS=10 k6/seat-map.js
```

| 환경변수 | 설명 | 기본값 |
|---|---|---|
| `BASE` | 대상 서버 주소. 필수 | |
| `EVENT_ID` | 공연 id. 필수 | |
| `PATHNAME` | `seats/layout` 또는 `seats/status`. 필수 | |
| `VUS` | 동시에 요청을 반복하는 가상 사용자 수 | 10 |
| `DURATION` | 실행 시간 | 20s |
| `ENCODING` | `Accept-Encoding` 값. 비우면 보내지 않는다 | |

## 재현할 때 지킬 것

- **공유 환경이 아니라 일회용 환경에서 돌린다.** 개발용 DB와 Redis, 이미 떠 있는 앱을 대상으로 삼지 않는다. 예: 앱 이미지를 빌드해 임시 Postgres, Redis와 같은 네트워크에 띄우고 `scripts/seed-event.sql`로 좌석 30,000석을 넣은 뒤 다른 포트(예: 18082)로 연다.
- 앱은 `docker-compose.app.yml`과 같은 메모리 제한(`mem_limit: 1g`)으로 띄운다. 제한이 없으면 Docker VM 전체 메모리를 기준으로 힙이 정해져 결과가 달라진다.
- VU는 응답을 받으면 쉬지 않고 다음 요청을 보내는 가상 사용자라서 "VU 50 = 사용자 50명"이 아니다. 믿을 만한 수치는 초당 처리량이다. 초당 도착 수를 고정하려면 `constant-arrival-rate` 시나리오가 필요하다.
- 부하기가 같은 머신에서 CPU를 나눠 쓰면 서버의 최대치가 아니라 그 조건에서의 값이 나온다.

## 측정 결과

이슈 #127 본문에 기존 `/seats`와 새 구조의 비교, 갱신 주기 수정 전후 결과를 적었다.
