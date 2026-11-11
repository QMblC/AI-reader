CREATE TABLE analysis_cache (
    cache_key varchar(64) PRIMARY KEY,
    translation text NOT NULL,
    explanation text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX analysis_cache_expiry ON analysis_cache (expires_at);

CREATE TABLE illustration_jobs (
    cache_key varchar(64) PRIMARY KEY,
    id uuid NOT NULL UNIQUE,
    scope varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED', 'GENERATING', 'READY', 'FAILED')),
    content_type varchar(32),
    error_code varchar(64),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX illustration_jobs_expiry ON illustration_jobs (expires_at);
