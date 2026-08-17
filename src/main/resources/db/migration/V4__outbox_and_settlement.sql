CREATE TABLE outbox (
    id              bigserial PRIMARY KEY,
    aggregate_type  varchar(50) NOT NULL,
    aggregate_id    bigint NOT NULL,
    event_type      varchar(50) NOT NULL,
    payload         jsonb NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    claimed_at      timestamptz,
    created_at      timestamptz NOT NULL,

    CONSTRAINT ck_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED'))
);

COMMENT ON TABLE outbox IS
    '상태 변경 트랜잭션 안에서 함께 INSERT한다. 커밋 후 발행하면 발행 실패 시 하위 처리가 변경을 영영 놓친다';
COMMENT ON COLUMN outbox.payload IS
    'jsonb를 쓰는 이유는 이벤트마다 필드가 달라 컬럼으로 펼치면 스키마가 계속 바뀌기 때문이다. 내부 검색이 가능해 장애 조사에도 유리하다';
COMMENT ON COLUMN outbox.status IS
    'PROCESSING이 필요한 이유는 SMS 발송 같은 외부 I/O를 트랜잭션 밖에서 해야 하기 때문이다. SKIP LOCKED로 락을 잡은 채 외부 호출을 하면 그동안 DB 커넥션이 묶인다';
COMMENT ON COLUMN outbox.claimed_at IS
    '소유권을 가져간 시각. 처리 중 인스턴스가 죽으면 PROCESSING에 영구히 묶이므로, 회수 배치가 이 시각이 오래된 행을 PENDING으로 되돌린다';
COMMENT ON COLUMN outbox.attempt_count IS
    '재시도 횟수. 임계치를 넘으면 FAILED로 표시하고 알림을 보낸다. 별도 DLQ 인프라 없이 컬럼 하나로 해결한다';

-- claim 대상 조회
CREATE INDEX ix_outbox_polling ON outbox (next_attempt_at)
 WHERE status = 'PENDING';

-- 죽은 인스턴스가 남긴 행 회수
CREATE INDEX ix_outbox_stale_claim ON outbox (claimed_at)
 WHERE status = 'PROCESSING';

CREATE TABLE settlement_daily (
    id             bigserial PRIMARY KEY,
    event_id       bigint NOT NULL REFERENCES event(id),
    settled_date   date NOT NULL,
    paid_count     integer NOT NULL DEFAULT 0,
    canceled_count integer NOT NULL DEFAULT 0,
    net_amount     bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    CONSTRAINT uk_settlement_event_date UNIQUE (event_id, settled_date)
);

COMMENT ON TABLE settlement_daily IS
    '일 단위 정산 집계. payment와 payment_history에서 재계산 가능한 파생 데이터다';

-- ShedLock. 정산 집계는 행 단위로 쪼갤 수 없어 한 인스턴스만 실행해야 한다.
-- outbox 폴링과 홀드 만료 정리는 반대로 SKIP LOCKED로 나눠 처리한다.
CREATE TABLE shedlock (
    name       varchar(64) PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at  timestamptz NOT NULL,
    locked_by  varchar(255) NOT NULL
);
