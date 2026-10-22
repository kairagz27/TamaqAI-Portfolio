-- Additive catalogue schema. No diary, authentication or financial rows change.
-- Roll back the application if necessary; retain these tables and immutable versions.
CREATE TABLE catalog_entries (
 entry_key text PRIMARY KEY,
 owner_id uuid REFERENCES users(id),
 catalog_id text NOT NULL,
 namespace text NOT NULL,
 search_text text NOT NULL,
 current_version text NOT NULL,
 created_at bigint NOT NULL
);
CREATE INDEX catalog_entries_owner ON catalog_entries(owner_id);
CREATE INDEX catalog_entries_id ON catalog_entries(catalog_id);
CREATE TABLE catalog_versions (
 version_id text PRIMARY KEY,
 entry_key text NOT NULL REFERENCES catalog_entries(entry_key) ON DELETE CASCADE,
 revision integer NOT NULL CHECK(revision > 0),
 document jsonb NOT NULL,
 created_at bigint NOT NULL,
 UNIQUE(entry_key,revision)
);
CREATE FUNCTION catalog_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Catalogue versions are immutable';
END;
$$;
CREATE TRIGGER catalog_no_version_update BEFORE UPDATE ON catalog_versions
 FOR EACH ROW EXECUTE FUNCTION catalog_version_immutable();
