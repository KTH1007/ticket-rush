# PG 구현 전환 절차 (가짜와 Toss)

PG 구현은 설정 `ticket-rush.payment.gateway`(환경 변수 `TICKET_RUSH_PAYMENT_GATEWAY`)가 `fake` 또는 `toss`로 정한다. 값이 없거나 다르면 기동이 실패한다. 가짜(`fake`)는 `local`, `docker`, `test` 프로필에서만 켜지고, 그 밖의 프로필에서 `fake`를 주면 기동이 실패한다. 값을 바꾸려면 재기동이 필요하다.

옛 `toss` 프로필은 더 이상 PG를 고르지 않는다. 그 프로필이 켜져 있는데 gateway가 `toss`가 아니면 기동이 실패한다.

## 결제가 남은 채로 바꾸면 안 되는 이유

가짜와 Toss는 결제를 다루는 방식이 달라서, 한쪽이 만든 결제를 다른 쪽이 이어받으면 돈과 기록이 어긋난다.

| 남아 있는 건 | 가짜가 처리하면 | Toss가 처리하면 |
|---|---|---|
| Toss가 승인한 `PENDING` | 가짜 조회는 항상 "없음"이라 회수 스케줄러가 `FAILED`로 확정한다. 고객은 돈을 냈는데 우리는 실패로 닫는다. 승인 요청이면 돈을 확인하지 않고 승인한다 | 정상 |
| Toss가 승인한 건의 `CANCELED`(환불 대기) | 가짜 환불은 승인만 하고 돈은 안 돌려주는데 `REFUNDED`로 기록한다 | 정상 |
| 가짜가 승인한 건(`pg_transaction_id`가 `FAKE-`로 시작) | 정상 | Toss는 그 id를 몰라 환불이 거절되고 시도를 소진해 사람이 처리해야 한다 |

## 가짜에서 Toss로

1. 결제로 들어오는 경로를 닫는다(대기열을 닫는다).
2. 남은 건이 없는지 확인한다. 아래 확인 SQL에서 `PENDING`과 `CANCELED`가 0이어야 한다. 있으면 끝날 때까지 기다리거나, 환불 필요와 한도 소진 건은 `payment-refund-manual-handling.md`의 절차로 닫는다.
3. 모든 인스턴스를 한꺼번에 바꾼다. 하나씩 바꾸면 중간에 두 구현이 섞여 `PaymentGatewayInconsistent` 알림이 5분 뒤 울린다.
4. 인스턴스마다 확인한다.
   - `/actuator/info`의 `payment.gateway`가 `toss`인지(인스턴스별 포트로 직접 호출한다. 외부 nginx는 `/actuator/`를 막는다)
   - 지표 `payment_gateway_info{type="toss"}`가 모든 인스턴스에서 보이고 `fake`가 없는지
   - 기동 로그에 INFO "PG는 Toss입니다"가 있고 WARN "가짜 PG"가 없는지
5. 샌드박스 키로 결제 한 건을 시험한 뒤 다시 연다.

## Toss에서 가짜로

운영에서는 허용하지 않는다(프로필 제한으로 기동이 실패한다). 개발 DB를 비운 뒤에만 한다. 실제 결제 이력이 있는 DB를 가짜로 돌리면 위 표대로 돈이 틀어진다.

## 확인 SQL

```sql
-- 남은 건수. PENDING과 CANCELED가 모두 0이어야 한다
SELECT status, count(*) AS cnt FROM payment WHERE status IN ('PENDING', 'CANCELED') GROUP BY status ORDER BY status;

-- 가짜가 처리한 결제와 실제 결제가 DB에 섞여 있는지
SELECT count(*) FILTER (WHERE pg_transaction_id LIKE 'FAKE-%') AS fake_rows,
       count(*) FILTER (WHERE pg_transaction_id IS NOT NULL AND pg_transaction_id NOT LIKE 'FAKE-%') AS real_rows
  FROM payment;
```

둘 다 0보다 크면 가짜와 실제 결제가 한 DB에 섞인 것이다. 운영 DB에서 이런 상태로 `fake`를 켜서는 안 된다.
