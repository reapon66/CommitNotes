CREATE TABLE auth_throttle (
 key_hash CHAR(64) PRIMARY KEY,
 attempt_count INT NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
