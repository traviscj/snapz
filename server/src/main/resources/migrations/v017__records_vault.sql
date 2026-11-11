ALTER TABLE records
  ADD COLUMN vault_id BIGINT NOT NULL DEFAULT 1,
  DROP INDEX u_ns_k,
  ADD UNIQUE KEY u_vault_ns_k (vault_id, ns, k)
