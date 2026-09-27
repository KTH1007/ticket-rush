ALTER TABLE reservation ADD COLUMN encrypted_phone bytea;

COMMENT ON COLUMN reservation.encrypted_phone IS
    '조회용 phone_hash(단방향)와 별개로, 발송(SMS)을 위해 원문 복원이 가능해야 해서 양방향 암호화로 저장한다. 기존 로우는 NULL(신규 예약부터 적용, 백필은 스코프 밖)';
