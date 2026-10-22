CREATE TABLE users (
 id UUID PRIMARY KEY, email TEXT UNIQUE, email_key TEXT UNIQUE, password_hash TEXT,
 name TEXT NOT NULL DEFAULT '', height_cm DOUBLE PRECISION, current_weight_kg DOUBLE PRECISION,
 target_weight_kg DOUBLE PRECISION, verified_at BIGINT, deleted_at BIGINT, created_at BIGINT NOT NULL,
 CHECK(height_cm IS NULL OR height_cm BETWEEN 50 AND 250),
 CHECK(current_weight_kg IS NULL OR current_weight_kg BETWEEN 20 AND 500),
 CHECK(target_weight_kg IS NULL OR target_weight_kg BETWEEN 20 AND 500)
);
ALTER TABLE invitations ADD CONSTRAINT invitations_user_fk FOREIGN KEY(user_id) REFERENCES users(id);
CREATE TABLE auth_sessions (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), created_at BIGINT NOT NULL,
 auth_time BIGINT NOT NULL, expires_at BIGINT NOT NULL, revoked_at BIGINT
);
CREATE TABLE refresh_tokens (
 token_hash TEXT PRIMARY KEY, session_id UUID NOT NULL REFERENCES auth_sessions(id),
 created_at BIGINT NOT NULL, used_at BIGINT, revoked_at BIGINT
);
CREATE INDEX refresh_session ON refresh_tokens(session_id);
CREATE TABLE email_actions (
 token_hash TEXT PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), purpose TEXT NOT NULL CHECK(purpose IN ('verify','reset')),
 created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, used_at BIGINT
);
CREATE INDEX email_action_user ON email_actions(user_id,purpose);
CREATE TABLE auth_attempts (id BIGSERIAL PRIMARY KEY, bucket TEXT NOT NULL, created_at BIGINT NOT NULL);
CREATE INDEX auth_attempt_bucket ON auth_attempts(bucket,created_at);
