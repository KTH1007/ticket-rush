# 느린 요청 추적

SLO(`docs/slo.md`)가 "어느 API가 느렸다"를 알려주면, 이 문서의 로그가 "어느 요청이, 어디서 시간을 썼나"를 알려준다. 부하 시험과 운영이 같은 방법을 쓴다.

## 남는 로그

| 위치 | 남는 것 | 남기는 기준 |
|---|---|---|
| nginx 접근 로그(표준 출력, JSON 한 줄) | 요청 id, 시각, 경로, 상태, `request_time`, 앱 구간 시간(`upstream_*`), 캐시 상태 | 100ms 이상이거나 500, 502, 504 응답 |
| 앱 로그(WARN, JSON) | `느린 요청: method, uri, status, elapsedMs`와 `traceId` | 기본 300ms 이상. `ticket-rush.observability.slow-request-ms`(환경변수 `TICKET_RUSH_OBSERVABILITY_SLOW_REQUEST_MS`)로 바꾼다. 측정 compose는 100ms |
| Postgres 로그 | 느린 쿼리와 잠금 대기 | 100ms 이상. 측정 compose에서만 켠다 |
| 응답 헤더 `X-Request-Id` | nginx가 붙인 요청 id | 모든 응답 |

- 요청을 전부 남기지 않는다. 초당 수천 건이라 전부 남기면 로그 쓰기가 부하가 된다. 기준 아래 요청은 로그에 없으므로 전체 요청 수와 분포는 k6 요약이나 Prometheus 히스토그램(`http_server_requests_seconds`)에서 본다.
- 쿼리 문자열과 요청 본문, 대기 토큰은 남기지 않는다. 경로만 남는다.
- 느린 요청 기준(nginx 100ms)은 SLO p95 중 가장 낮은 값(순번 조회, 좌석 상태 100ms)에 맞췄다.

## 요청 id로 로그를 잇는다

```
클라이언트 -> nginx($request_id) -> 앱(X-Request-Id를 traceId로 사용) -> 앱 로그
              응답 헤더 X-Request-Id
ALB만 있을 때: ALB(X-Amzn-Trace-Id의 Root) -> 앱(Root를 traceId로 사용). ALB 로그의 trace_id 열과 같다
```

- nginx는 `proxy_set_header X-Request-Id $request_id`로 앱에 넘기고, 응답에도 같은 헤더를 붙인다. 사용자가 신고할 때 이 값을 알려주면 로그를 바로 찾는다.
- 앱은 들어온 값이 영문, 숫자, `-`로 8~64자일 때만 traceId로 쓰고, 아니면 새로 만든다(직접 들어온 값이 로그를 오염시키지 못하게).
- 앱 로그는 `docker` 프로필에서 JSON이고 `traceId` 필드로 찾는다: `docker logs <앱 컨테이너> | grep <요청 id>`

## 분석 스크립트

부하 시험(`k6/measure-queue.sh`)은 단계마다 로그를 모아 `results.md`에 붙이고 `<단계>.nginx.log`, `.app.log`, `.pg.log`를 결과 폴더에 남긴다. 직접 돌릴 때는 이렇게 한다.

```
docker logs <nginx 컨테이너> 2> /dev/null > nginx.log
docker logs <앱 컨테이너> > app.log 2>&1
python3 k6/slow-requests.py nginx nginx.log --app app.log --k6 흐름요약.json
python3 k6/slow-requests.py app app.log
python3 k6/slow-requests.py pg postgres.log
```

`nginx` 모드의 표를 읽는 법이다.

| 보이는 모양 | 뜻 | 다음에 볼 곳 |
|---|---|---|
| 앱 구간이 대부분이고 앱 로그에도 같은 id가 있음 | 앱 안에서 느렸다 | 앱 로그의 경로, Postgres 느린 쿼리, GC |
| 앱 구간이 대부분인데 앱 로그에는 없음 | 앱 처리는 빨랐고 앱에 닿기 전이나 응답을 내기 전에 기다렸다 | 앱 스레드와 연결 풀 대기, CPU 포화 |
| 앱 밖 구간이 대부분 | nginx 대기, 요청을 받는 시간, 클라이언트가 느리게 받는 시간 | 큰 응답(좌석 배치), 클라이언트 회선 |
| 느린 요청이 한두 시간대에 몰림 | 그 순간의 사건 | 그 시각의 GC, 스케줄러, 캐시 갱신, 배포 |
| 느린 요청이 고르게 퍼짐 | 처리 용량 부족 | CPU, 연결 풀 사용률 |
| 전체 요청 대비 p95 초과 비율이 5% 이상 | nginx 쪽에서 본 SLO 위반 | k6 판정과 대조 |

- "p95 초과 비율"은 기준 시간 이상 로그에서 SLO p95를 넘은 건수를 k6의 전체 요청 수로 나눈 값이다. k6 판정과 다른 쪽에서 같은 결론이 나오는지 교차 확인하는 용도다.

## nginx 필드와 ALB 필드

ALB 접근 로그의 필드 정의는 AWS 공식 문서(Application Load Balancer의 access logs) 기준이다. ALB는 로그를 5분 단위 파일로 S3에 쓰고, 기본은 꺼져 있다.

| 보려는 것 | nginx | ALB | 차이 |
|---|---|---|---|
| 요청 전체 | `request_time` | `request_processing_time` + `target_processing_time` + `response_processing_time` | nginx는 클라이언트에 다 보낼 때까지, ALB는 클라이언트에 보내기 시작할 때까지라 ALB 합이 더 짧다 |
| 앱이 응답 헤더를 내기까지 | `upstream_header_time` | `target_processing_time` | ALB는 WAF가 없으면 POST 본문을 클라이언트가 보내는 시간도 여기에 들어간다 |
| 앱 응답을 다 받기까지 | `upstream_response_time` | 없음 | 큰 응답은 ALB 로그로 본문 전송 시간을 못 가른다 |
| 앱에 연결하기까지 | `upstream_connect_time` | `request_processing_time`에 포함 | 연결 실패는 ALB에서 세 시간이 모두 -1 |
| 응답 코드 | `status`, upstream 상태 | `elb_status_code`, `target_status_code` | 둘이 다르면 ALB가 직접 답한 것(502, 503, 504) |
| 요청 id | `request_id`(= `X-Request-Id`) | `trace_id`(= `X-Amzn-Trace-Id`) | 앱은 둘 다 traceId로 받는다 |
| 시각 | `time_iso8601` | `time`(응답 생성), `request_creation_time`(요청 받은 때) | |

Athena에서 ALB 로그로 느린 요청을 찾는 쿼리 예시다(테이블과 열 이름은 Athena 문서의 ALB 테이블 정의를 따른다).

```sql
SELECT time, elb_status_code, target_status_code, request_url, trace_id,
       request_processing_time + target_processing_time + response_processing_time AS total_seconds
FROM alb_logs
WHERE target_processing_time > 0.1
ORDER BY total_seconds DESC
LIMIT 20
```

## 한계

- 이 저장소에는 ALB가 없어서 ALB 쪽은 문서 기준이다. 쿼리와 대응표는 실행해 보지 못했다.
- 기준 아래 요청은 로그에 없다. 분포 전체는 k6 요약과 Prometheus에서 본다.
- nginx의 `request_time`은 클라이언트가 느리게 받으면 늘어난다. 서버만의 시간은 `upstream_header_time`과 앱 로그로 가른다.
- 느린 요청 기준(nginx 100ms)은 설정에 박혀 있다. 바꾸려면 `docker/nginx/nginx.conf`의 `$slow_request` 맵을 고친다.
