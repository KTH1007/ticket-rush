# 티켓 예매 시스템

오픈런 트래픽을 견디는 콘서트 티켓 예매 시스템.

비회원 지정석 판매를 기준으로 대량 접속을 대기열로 흡수하고, 동시 요청에서도 좌석 정합성을 보장하는 것을 목표로 한다.

기능을 넓히기보다 하나의 예매 흐름을 끝까지 구현하고, 설계 판단은 부하 테스트와 동시성 테스트로 검증한다.

---

## 목표

예매 오픈 순간 트래픽이 급증하는 상황에서 다음을 만족한다.

1. 대량 접속을 대기열로 흡수한다.
2. 같은 좌석이 중복 판매되지 않도록 한다.
3. 결제와 좌석 상태의 정합성을 보장한다.
4. 중복 요청을 멱등하게 처리한다.
5. 외부 장애가 전체 예매 흐름으로 전파되지 않도록 한다.
6. 실패한 작업을 재시도하고 재처리할 수 있도록 한다.

### 목표 규모

| 항목 | 목표 |
| --- | --- |
| 좌석 | 30,000석 |
| 최대 대기열 유입 | 500,000명 |
| 대기열 등록 | 2,000 TPS |
| Active 승격 및 DB 반영 | 100 TPS |

목표 수치는 가정치이며 부하 테스트 결과에 따라 갱신한다.

---

## 사용자 흐름

```
대기열 등록 -> 순번 조회 -> Active 승격 -> 좌석 조회
-> 좌석 선택 -> 좌석 선점 -> 결제 -> 예매번호 발급
```

예매 후 예매번호와 전화번호를 이용해 조회 및 취소할 수 있다.

---

## 주요 기능

### 대기열

- 선착순 대기열
- 순번 조회
- TPS 기반 Active 승격
- 이탈자 정리

### 좌석

- 지정석 배치도 조회
- 좌석 상태 비트맵
- 최대 2석 선점
- all-or-nothing 선점
- 홀드 만료
- 결제 전 좌석 변경
- DB 기반 최종 정합성 보장

### 결제

- MockPG 연동
- 서버 측 금액 검증
- Idempotency-Key 기반 멱등성
- Circuit Breaker
- 결제 실패 보상 처리

### 취소 및 후처리

- 예약 취소
- 좌석 반환
- Outbox 기반 SMS 처리
- 실패 작업 재시도
- 실패 작업 재처리
- 정산 집계

### 보호 기능

- 1인 2매 제한
- API Rate Limit
- 매진 Kill Switch
- 표준 에러 응답
- Trace ID

---

## 핵심 설계

### 좌석 정합성

Redis를 좌석 최종 판정자로 사용하지 않는다.

Redis는 트래픽을 줄이는 용도로 사용하고, 좌석 최종 상태는 PostgreSQL의 조건부 UPDATE로 결정한다.

```
Redis -> 빠른 상태 확인 및 부하 제어
PostgreSQL -> 좌석 최종 정합성
```

### 멱등성

동일한 요청이 여러 번 들어와도 비즈니스 결과가 중복 생성되지 않도록 한다.

```
Idempotency-Key
-> 기존 요청 확인
-> 기존 결과 반환

동시 요청
-> DB UNIQUE 제약으로 최종 방어
```

### Outbox

예매 완료 후처리는 Outbox에 기록한 뒤 비동기로 처리한다.

```
Transaction -> Outbox -> Worker -> 외부 처리
                         |
                         -> 실패 -> Retry -> 재처리
```

### 메시지 브로커

v1에서는 Kafka를 사용하지 않는다.

현재 구조에서는 Outbox와 DB Polling으로 충분하며, Kafka가 필요한 조건과 v2 확장 설계는 별도 문서에서 다룬다.

---

## 실행 방법

인프라만 컨테이너로 띄우고 앱은 로컬에서 직접 실행한다.

```bash
# 1. 환경 변수 준비
cp .env.example .env   # PHONE_HMAC_KEY, GRAFANA_ADMIN_PASSWORD 채우기

# 2. 인프라 기동 (PostgreSQL, Redis, Flyway 마이그레이션)
docker compose up -d

# 3. 앱 실행 (IDE 또는 CLI, local 프로필이 기본값)
./gradlew bootRun
```

Prometheus/Grafana까지 같이 보려면 `docker compose --profile observability up -d`.

전체 빌드 검증(컴파일 + 테스트 + detekt + ktlint + 모듈 경계 검사):

```bash
./gradlew build
```

앱 2대 + nginx로 다중 인스턴스를 검증하려면:

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build
```

---

## 실행 환경

```
nginx -> App 1
      -> App 2

App -> PostgreSQL
    -> Redis
    -> MockPG

Prometheus -> App
Grafana -> Prometheus
```

앱 서버는 2개 인스턴스로 구성하고 PostgreSQL과 Redis는 단일 인스턴스로 구성한다.

실제 다중 노드 환경이 아닌 동일 호스트의 컨테이너 2개를 사용하며, 네트워크 파티션이나 실제 인프라 장애는 검증 범위에서 제외한다.

---

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Kotlin |
| Runtime | JDK 25 |
| Framework | Spring Boot 4 |
| Architecture | Spring Modulith |
| Database | PostgreSQL |
| Cache / Queue | Redis, Lettuce |
| Persistence | Spring Data JPA, QueryDSL |
| Migration | Flyway |
| Reliability | Resilience4j, Outbox |
| Test | JUnit 5, MockK, Testcontainers, k6 |
| Monitoring | Actuator, Micrometer, Prometheus, Grafana |
| Infrastructure | Docker Compose, nginx |

---

## 검증

단순 기능 테스트보다 동시성과 부하 상황을 중심으로 검증한다.

### 동시성

- 같은 좌석 동시 요청
- 동일 Idempotency-Key 동시 요청
- 2개 앱 인스턴스의 동시 요청
- DB Row Lock 경합

### 부하

- App 1대 기준선
- App 2대 확장성
- 대기열 2,000 TPS
- Active 승격 100 TPS
- 인기 좌석 집중 요청

### 장애

- Redis 장애
- MockPG timeout
- 결제 연속 실패
- Outbox 처리 실패

실측 결과와 병목 원인을 기록하고 필요하면 설정과 설계를 다시 조정한다.

### 검증하지 않는 것

로컬 컨테이너 2개는 프로세스 분리는 재현하지만 실제 다중 노드 환경은 아니다. 아래는 설계 근거로만 남기고 실측 검증은 하지 않는다.

- 무상태 인증
- 로컬 캐시 편차
- 인스턴스 강제 종료 시 락 해제와 인계
