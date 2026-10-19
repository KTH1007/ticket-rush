# 환불 수동 처리 절차

사람이 처리해야 하는 결제는 두 종류다. 둘 다 자동으로 닫지 않는다. 돈이 움직이는 판단이라 사람이 Toss에서 확인한 뒤 DB에서 닫는다.

| 종류 | 상태 | 고객 상황 | 알림 |
|---|---|---|---|
| 환불 필요 | `PENDING`이고 `refund_required_at`이 있음 | Toss는 승인했는데 예약이 만료돼 돈만 나가고 티켓이 없음. 부분 취소로 수동 확인이 필요한 건도 같은 표시를 씀 | `PaymentRefundRequired` |
| 환불 한도 소진 | `CANCELED`이고 `refund_attempt_count`가 `refund-max-attempts` 이상 | 취소는 접수됐지만 자동 환불이 모두 실패해 돈이 안 돌아감 | `PaymentRefundExhausted` |

알림은 Prometheus의 Alerts 화면에서 보고, 건수 추이는 Grafana의 "결제 환불 운영" 대시보드에서 본다. 건수는 인스턴스마다 전체를 보고하므로 `max`로 합친다. `PaymentRefundBacklogStale`이나 `PaymentRefundBacklogAbsent`가 울리면 건수 자체를 믿을 수 없으니 DB 연결부터 확인한다.

## 절차

스크립트는 `scripts/refund/`에 있고 `psql`로 실행한다.

1. **대상을 찾는다.**
   - 환불 필요: `psql -f scripts/refund/list-refund-required.sql`
   - 소진: `psql -v max_attempts=5 -f scripts/refund/list-refund-exhausted.sql` (`max_attempts`는 설정 `refund-max-attempts` 값)
   - 마지막 이력 사유(`last_reason`)에 왜 이 상태가 됐는지 남아 있다.
2. **Toss 콘솔에서 확인하고 처리한다.** 환불 필요 건은 `toss_payment_key`, 소진 건은 `pg_transaction_id`(Toss paymentKey)로 찾는다. 이미 환불됐으면 그대로 두고, 안 됐으면 콘솔에서 환불한다.
3. **DB에서 닫는다.** 메모에는 누가 어떻게 처리했는지 남긴다. 이력 사유는 200자까지만 들어간다.
   - 환불 필요: `psql -v payment_id=123 -v note='Toss 콘솔에서 환불함' -f scripts/refund/resolve-refund-required.sql` (`PENDING -> FAILED`)
   - 소진: `psql -v payment_id=123 -v max_attempts=5 -v note='Toss 콘솔에서 환불함' -f scripts/refund/resolve-refund-exhausted.sql` (`CANCELED -> REFUNDED`)
4. **결과를 확인한다.** 마지막 줄의 `resolved_rows`가 1이어야 한다. 0이면 아무것도 바뀌지 않은 것이다. id가 맞는지, 상태가 조건에 맞는지(소진 건은 `max_attempts`가 설정값과 같은지)를 확인한다. `note`를 빼면 문법 오류로 멈추는데 의도한 동작이다.
5. 건수는 다음 갱신(기본 15초)에 내려가고 알림도 꺼진다. 처리 이력은 `payment_history`에 남는다.

## 알아 둘 점

- 환불 필요 건은 닫은 뒤에도 `refund_required_at`이 남는다. 같은 예약의 새 승인 요청이 계속 막히게 하려는 것이다. 정산은 `SUCCESS`만 집계하므로 닫힌 건은 정산에 들어가지 않는다.
- 소진 건의 시도 횟수는 초기화하지 않는다. 환불 멱등키가 예약 키와 시도 횟수로 만들어지고 Toss가 거절 응답을 키에 묶어 재생하므로, 횟수를 0으로 돌리면 이전 거절이 그대로 돌아올 수 있다. 자동 재시도를 다시 켜고 싶으면 설정 `refund-max-attempts`를 올려 배포한다. 이미 소진된 건도 다음 키로 이어서 시도한다.
- 이력 사유에 "Toss에서 외부 취소 확인"이 있으면 우리 환불 요청이 아니라 운영자가 Toss에서 직접 취소한 건이다.
- 스크립트는 상태 조건이 맞을 때만 바꾸므로 같은 명령을 다시 실행해도 안전하다.
