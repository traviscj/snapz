UPDATE vaults SET token = CONCAT('v', SUBSTRING(MD5(CONCAT(id, ':', RAND(), ':', created_at_ms)), 1, 20)) WHERE token IS NULL
