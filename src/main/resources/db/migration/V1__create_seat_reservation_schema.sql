CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (show_id, user_id, idempotency_key)
);

CREATE TABLE seats (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_number VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'CONFIRMED')),
    reservation_id UUID REFERENCES reservations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (show_id, seat_number),
    CHECK ((status = 'AVAILABLE' AND reservation_id IS NULL) OR
           (status = 'CONFIRMED' AND reservation_id IS NOT NULL))
);

CREATE TABLE user_show_locks (
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    user_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (show_id, user_id)
);

CREATE INDEX reservations_show_user_status_idx
    ON reservations (show_id, user_id, status);
CREATE INDEX seats_show_status_idx
    ON seats (show_id, status);