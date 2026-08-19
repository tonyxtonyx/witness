ALTER TABLE service_accounts
  ADD COLUMN requires_rotation BOOLEAN NOT NULL DEFAULT TRUE;

CREATE UNIQUE INDEX service_accounts_api_key_hash_unique
  ON service_accounts(api_key_hash);
