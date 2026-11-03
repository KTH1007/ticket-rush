# e2e 시나리오

사용자가 실제로 겪는 흐름을 처음부터 끝까지 이어서 검증하는 시나리오 목록이다. 각 단계는 단위, 컨트롤러 테스트로 이미 따로 검증하고 있으므로, 이 문서와 e2e 테스트의 목적은 **단계를 이어 붙였을 때 생기는 문제**(모듈 사이에 넘기는 토큰과 상태, 실제 Redis와 Postgres, 스케줄러가 끼는 흐름)를 찾는 것이다.

## 실행

```
./gradlew e2eTest                                  # 전체
./gradlew e2eTest --tests 'com.ticketrush.e2e.PaymentE2eTest'
```

- `@Tag("e2e")`로 묶어 기본 `./gradlew build`에서는 뺀다(전체 약 1분).
- Testcontainers로 Postgres와 Redis를 띄우고, 앱은 실제 HTTP 포트로 기동한다. Toss는 `127.0.0.1`에만 바인드한 WireMock이 대신한다.
- 스케줄러가 실제로 돌도록 `application-e2e.yml`이 주기를 짧게 되돌린다(승격 200ms, 홀드 만료 정리 500ms, 결제 회수 500ms, 환불 재시도 500ms). 홀드 TTL은 10초, Toss 타임아웃은 연결 300ms와 읽기 700ms다.
- 테스트마다 새 공연과 새 사용자 IP(`X-Forwarded-For`)와 새 전화번호를 써서 서로의 상태(대기열, 좌석, IP 한도)에 끼지 않는다.
- 설정이 달라야 하는 시나리오(대기열 상한과 IP 한도, 승격 끔, 활성 TTL 1초, 서킷브레이커 격리)는 `@TestPropertySource`로 컨텍스트를 나눈다.

## 전제와 한계

- 개별 오류와 규칙은 단위, 컨트롤러, 서비스, 동시성 테스트가 이미 검증한다. "기존 테스트" 열은 오류 코드와 예외 클래스 이름으로 `src/test`를 검색해 대조한 것이고, 세부 케이스는 각 테스트에서 확인한다.
- 이 e2e가 생기기 전에는 줄서기, 홀드, 결제, 조회, 취소 중 둘 이상을 이어서 거치는 테스트 파일이 없었다.
- 결제는 가짜 Toss(WireMock)라 실제 Toss의 지연과 오류 분포는 반영하지 못한다.
- Redis 장애는 메모리 한도로 쓰기를 거부시키는 방식만 다룬다. 연결 끊김은 단위 테스트로만 확인한다.


## 정상 흐름

| ID | 시나리오 | 기대 결과 | 기존 테스트 | e2e |
|---|---|---|---|---|
| E01 | 구매 한 바퀴: 줄서기 -> 순번 조회로 승격 확인 -> 좌석 홀드 -> 결제 승인 -> 예약 확정 | 줄서기 201, 조회 200, 홀드 201, 결제 201. PG에는 홀드가 정한 주문과 금액이 간다 | 단계별로만(`QueueControllerTest`, `SeatHoldControllerTest`, `PaymentControllerTest`) | `PurchaseJourneyE2eTest` |
| E02 | 예약 조회: 예약번호와 전화번호로 조회 | 200, 상태 `PAID`, 공연과 금액과 좌석 수 일치 | `ReservationLookupControllerTest` | `PurchaseJourneyE2eTest` |
| E03 | 취소와 환불: 확정된 예약을 취소 | 200, 결제 `REFUNDED`, 조회 상태 `CANCELED`, 돌아온 좌석을 다른 사용자가 홀드 | `PaymentCancelControllerTest`, `PaymentRefunderTest` | `PurchaseJourneyE2eTest` |
| E28 | 읽기 경로: 공연 목록, 상세, 좌석 상태, 좌석 배치 | 200, 없는 공연은 404 `EVENT_NOT_FOUND` | `EventControllerTest`, `SeatMapQueryServiceTest` | `ReadPathsE2eTest` |
| E30 | 요청 지연이 SLO 임계값 버킷으로 지표에 남음 | `/actuator/prometheus`에 `http_server_requests_seconds_bucket`과 `le="0.3"`이 있음 | 없음 | `ReadPathsE2eTest` |

## 대기열

| ID | 시나리오 | 기대 결과 | 기존 테스트 | e2e |
|---|---|---|---|---|
| E04 | 오픈 전 줄서기 | 409 `QUEUE_NOT_OPEN` | `QueueControllerTest` | `QueueE2eTest` |
| E05 | 공연 시작 뒤 줄서기 | 409 `QUEUE_CLOSED` | `QueueControllerTest` | `QueueE2eTest` |
| E06 | 없는 공연에 줄서기 | 404 `EVENT_NOT_FOUND` | `QueueControllerTest` | `QueueE2eTest` |
| E07 | 대기열이 상한에 닿음 | 503 `QUEUE_FULL`과 `Retry-After` 5~10초, 이미 선 사람의 조회는 계속 200 | `QueueRegisterCapTest`, `QueueRegisterCapConcurrencyTest` | `QueueLimitsE2eTest` |
| E08 | 같은 IP가 한도를 넘겨 줄서기 | 429 `QUEUE_REGISTER_RATE_LIMITED`와 `Retry-After` | `QueueRegisterIpLimitTest` | `QueueLimitsE2eTest` |
| E09 | 없는 대기 토큰으로 순번 조회 | 401 `INVALID_QUEUE_TOKEN` | `QueueQueryServiceTest`, `QueueControllerTest` | `QueueE2eTest` |
| E10 | 승격 전에 좌석 홀드 | 403 `QUEUE_NOT_ACTIVE` | `QueueCommandServiceAdmitTest`, `SeatHoldControllerTest` | `QueueLimitsE2eTest` |
| E11 | 활성 시간이 지난 뒤 홀드 | 403 `QUEUE_ACTIVE_EXPIRED` | `QueueControllerTest`, `SeatHoldControllerTest` | `QueueActiveExpiryE2eTest` |
| E12 | 활성 토큰을 다른 전화번호로 사용 | 403 `QUEUE_PHONE_MISMATCH` | `SeatHoldControllerTest`, `QueueBindScriptTest` | `QueueE2eTest` |

## 좌석 홀드

| ID | 시나리오 | 기대 결과 | 기존 테스트 | e2e |
|---|---|---|---|---|
| E13 | 여러 사용자가 같은 좌석을 동시에 홀드 | 한 명만 201, 나머지는 409 `SEAT_ALREADY_HELD` | `ReservationCommandServiceConcurrencyTest` | `HoldE2eTest` |
| E14 | 한 전화번호가 두 장을 넘겨 홀드 | 409 `RESERVATION_LIMIT_EXCEEDED` | `ReservationCommandServiceTest` | `HoldE2eTest` |
| E15 | 잘못된 좌석 선택 | 없는 좌석 404 `SEAT_NOT_FOUND`, 좌석이 없거나 3장 이상은 400 | `SeatSelectionTest`, `ReservationCommandServiceTest` | `HoldE2eTest` |
| E16 | 홀드가 만료되면 좌석이 다시 풀림 | 만료 뒤 다른 사용자가 홀드할 수 있고 이전 예약은 `EXPIRED` | `HoldExpirySchedulerTest`(스케줄러만) | `HoldE2eTest` |

## 결제

| ID | 시나리오 | 기대 결과 | 기존 테스트 | e2e |
|---|---|---|---|---|
| E17 | 결제 거절 뒤 같은 예약으로 다시 시도 | 409 `PAYMENT_DECLINED`, 재시도 201, 예약 `PAID` | `PaymentCommandServiceTest`, `PaymentControllerTest` | `PaymentE2eTest` |
| E18 | 같은 결제 요청을 동시에 두 번 | PG 승인은 한 번이고 예약은 한 번만 확정 | `ChargeIdempotencyKeyTest`, `PaymentCommandServiceClaimRaceTest` | `PaymentE2eTest` |
| E19 | 홀드 만료 임박 시 결제 시작 | 409 `RESERVATION_HOLD_EXPIRING`, PG 호출 없음 | `PaymentControllerTest`, `PaymentHoldPolicyGuardTest` | `PaymentE2eTest` |
| E20 | 홀드 토큰이 다름 | 401 `HOLD_TOKEN_MISMATCH`, PG 호출 없음 | `PaymentCommandServiceTest`, `PaymentControllerTest` | `PaymentE2eTest` |
| E21 | PG 장애가 이어져 Circuit Breaker가 열림 | 503 `PAYMENT_GATEWAY_UNAVAILABLE`, 열린 뒤에는 PG까지 가지 않음, 복구되면 다시 승인 | `TossPaymentGatewayAdapterCircuitBreakerTest` | `PaymentGatewayCircuitE2eTest` |
| E22 | PG 응답이 없음(타임아웃) | 503 뒤 결제 `PENDING`, 회수 스케줄러가 같은 멱등키로 다시 승인해 예약 `PAID` | `PaymentReclaimSchedulerTest`(스케줄러만) | `PaymentE2eTest` |

## 조회와 취소

| ID | 시나리오 | 기대 결과 | 기존 테스트 | e2e |
|---|---|---|---|---|
| E23 | 틀린 전화번호로 예약 조회를 반복 | 404 `RESERVATION_LOOKUP_FAILED`, 한도를 넘으면 429 `RESERVATION_LOOKUP_RATE_LIMITED`와 `Retry-After` | `ReservationLookupIpLimitTest`, `ReservationLookupControllerTest` | `LookupCancelE2eTest` |
| E24 | 이미 취소된 예약을 다시 취소 | 409 `RESERVATION_ALREADY_CANCELED` | `PaymentCancelCommandServiceTest` | `LookupCancelE2eTest` |
| E25 | 환불이 계속 실패 | 취소 응답은 200이고 결제는 `CANCELED`, 환불 시도는 5번에서 멈춤 | `PaymentRefundRetrySchedulerTest` | `PaymentE2eTest` |
| E29 | 결제 확정과 취소 뒤 알림(Outbox) | `RESERVATION_PAID`, `RESERVATION_CANCELED` 행이 `DONE`이 됨 | `OutboxRelaySchedulerTest`, `FakeSmsGatewayAdapterTest` | `NotificationE2eTest` |

## 인프라 장애

| ID | 시나리오 | 기대 결과 | 기존 테스트 | e2e |
|---|---|---|---|---|
| E26 | Redis가 메모리 한도에 닿아 쓰기를 거부 | 새 줄서기 503 `TEMPORARILY_UNAVAILABLE`과 `Retry-After`, 이미 선 사람의 조회는 200, 풀리면 바로 회복 | `GlobalExceptionHandlerTest`, `RedisFailuresTest`(단위) | `RedisFailureE2eTest` |
| E27 | Redis가 재시작돼도 대기 순번이 유지됨 | 재시작 뒤 같은 토큰으로 조회 가능 | 수동 스크립트 `scripts/redis-lab/app-aof.sh` | 자동화하지 않음(볼륨이 있는 컨테이너 재시작을 Testcontainers로 흉내 내기 어려워 수동 스크립트 유지) |
