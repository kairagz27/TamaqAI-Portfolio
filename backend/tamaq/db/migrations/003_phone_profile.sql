ALTER TABLE users ADD COLUMN phone TEXT UNIQUE;
ALTER TABLE users ADD COLUMN phone_verified_at BIGINT;
ALTER TABLE users ADD COLUMN avatar_key TEXT;
CREATE TABLE sms_challenges (
 id UUID PRIMARY KEY, phone TEXT NOT NULL, code_hash TEXT NOT NULL,
 created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 consumed_at BIGINT
);
CREATE INDEX sms_phone_created ON sms_challenges(phone,created_at);
