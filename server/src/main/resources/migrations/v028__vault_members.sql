CREATE TABLE vault_members (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  vault_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  slug VARCHAR(96) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY u_user_slug (user_id, slug),
  UNIQUE KEY u_vault_user (vault_id, user_id)
)
