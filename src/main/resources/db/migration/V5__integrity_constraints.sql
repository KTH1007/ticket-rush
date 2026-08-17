ALTER TABLE seat
    ADD CONSTRAINT ck_seat_lifecycle CHECK (
        (
            status = 'AVAILABLE'
            AND reservation_id IS NULL
            AND phone_hash IS NULL
            AND slot_no IS NULL
            AND hold_expires_at IS NULL
        )
        OR
        (
            status = 'HELD'
            AND reservation_id IS NOT NULL
            AND phone_hash IS NOT NULL
            AND slot_no IS NOT NULL
            AND hold_expires_at IS NOT NULL
        )
        OR
        (
            status = 'SOLD'
            AND reservation_id IS NOT NULL
            AND phone_hash IS NOT NULL
            AND slot_no IS NOT NULL
        )
    );

ALTER TABLE reservation
    ADD CONSTRAINT ck_reservation_amount
        CHECK (amount >= 0),
    ADD CONSTRAINT ck_reservation_holding_expiry
        CHECK (status <> 'HOLDING' OR hold_expires_at IS NOT NULL);

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_amount
        CHECK (amount >= 0),
    ADD CONSTRAINT ck_payment_success_paid_at
        CHECK (status <> 'SUCCESS' OR paid_at IS NOT NULL);

ALTER TABLE outbox
    ADD CONSTRAINT ck_outbox_attempt_count
        CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_outbox_claim_state
        CHECK (
            (status = 'PENDING' AND claimed_at IS NULL)
            OR (status = 'PROCESSING' AND claimed_at IS NOT NULL)
            OR status IN ('DONE', 'FAILED')
        );
