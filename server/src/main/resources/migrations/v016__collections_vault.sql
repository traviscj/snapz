ALTER TABLE collections
  ADD COLUMN vault_id BIGINT NOT NULL DEFAULT 1,
  DROP INDEX u_slug,
  ADD UNIQUE KEY u_vault_slug (vault_id, slug)
