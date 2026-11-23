CREATE TABLE guest_sessions (
    id uuid PRIMARY KEY,
    app_id text NOT NULL,
    refresh_token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX guest_sessions_expiry ON guest_sessions (expires_at);

CREATE TABLE guest_access_tokens (
    token_hash varchar(64) PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES guest_sessions (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX guest_access_tokens_session ON guest_access_tokens (session_id);
CREATE INDEX guest_access_tokens_expiry ON guest_access_tokens (expires_at);

CREATE TABLE api_usage (
    operation varchar(32) NOT NULL,
    subject varchar(64) NOT NULL,
    window_end timestamptz NOT NULL,
    used integer NOT NULL CHECK (used > 0),
    PRIMARY KEY (operation, subject, window_end)
);
CREATE INDEX api_usage_expiry ON api_usage (window_end);
