-- 테스트 전용 테이블. BaseEntity(공통 created_at/updated_at) 검증만을 위해 존재.
-- 운영 스키마(db/migration)와 무관하고, 버전 번호가 없는 Repeatable Migration이라
-- 실제 도메인 마이그레이션 번호와 충돌할 일이 구조적으로 없다.
DROP TABLE IF EXISTS base_entity_test_probe;

CREATE TABLE base_entity_test_probe (
    id         bigserial PRIMARY KEY,
    -- 실제 도메인 의미 없음. updatedAt 변경을 관찰하려면 바뀔 컬럼이 하나 필요하다.
    note       varchar(50),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
