-- Spring Modulith(spring-modulith-starter-jpa)가 내부적으로 쓰는 이벤트 발행
-- 레지스트리 테이블. ddl-auto가 validate라 Hibernate가 만들어주지 않으므로
-- 직접 마이그레이션으로 제공해야 한다.
--
-- 컬럼/제약은 추측이 아니라 pinned 버전(spring-modulith 2.1.0)이 스크래치 DB에
-- ddl-auto=create로 실제로 만든 스키마를 그대로 옮긴 것이다. 버전이 바뀌면
-- 이 스크립트도 같은 방식으로 다시 뽑아 맞춰야 한다.
CREATE TABLE event_publication (
    id                      uuid PRIMARY KEY,
    completion_attempts     integer NOT NULL,
    completion_date         timestamptz,
    last_resubmission_date  timestamptz,
    publication_date        timestamptz NOT NULL,
    event_type              varchar(255) NOT NULL,
    listener_id             varchar(255) NOT NULL,
    serialized_event        varchar(255) NOT NULL,
    status                  varchar(255),
    CONSTRAINT event_publication_status_check
        CHECK (status IN ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED'))
);

COMMENT ON TABLE event_publication IS
    'Spring Modulith 내부 테이블. 애플리케이션 도메인 스키마가 아니다';
