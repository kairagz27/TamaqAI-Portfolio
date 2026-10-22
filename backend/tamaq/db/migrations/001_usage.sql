CREATE TABLE invitations (
 principal TEXT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 user_id UUID UNIQUE, linked_operation UUID UNIQUE
);
CREATE TABLE guests (
 token_hash TEXT PRIMARY KEY, created BIGINT NOT NULL, principal TEXT NOT NULL REFERENCES invitations(principal), expires_at BIGINT NOT NULL
);
CREATE TABLE requests (
 guest TEXT NOT NULL REFERENCES invitations(principal), key TEXT NOT NULL, digest TEXT NOT NULL,
 created BIGINT NOT NULL, status TEXT NOT NULL, reserved BIGINT NOT NULL CHECK(reserved>=0),
 charged BIGINT NOT NULL DEFAULT 0 CHECK(charged>=0), body TEXT, usage TEXT, PRIMARY KEY(guest,key)
);
CREATE INDEX request_quota ON requests(guest,created);
CREATE INDEX request_key ON requests(key);
CREATE TABLE registration(id BIGSERIAL PRIMARY KEY, created BIGINT NOT NULL, principal TEXT);
CREATE TABLE budget (
 id INTEGER PRIMARY KEY CHECK(id=1), deleted_spend BIGINT NOT NULL DEFAULT 0 CHECK(deleted_spend>=0),
 halted INTEGER NOT NULL DEFAULT 0, global_cap BIGINT NOT NULL, pilot_cap BIGINT NOT NULL,
 verification_cap BIGINT, verification_baseline BIGINT
);
CREATE TABLE import_manifest (id INTEGER PRIMARY KEY CHECK(id=1), source_digest TEXT NOT NULL, imported_at BIGINT NOT NULL);
