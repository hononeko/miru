ALTER TABLE auth_accounts ADD COLUMN auth_provider TEXT NOT NULL DEFAULT 'LOCAL';
ALTER TABLE auth_accounts ADD COLUMN external_subject TEXT;

CREATE UNIQUE INDEX idx_auth_accounts_provider_subject
    ON auth_accounts (auth_provider, external_subject)
    WHERE external_subject IS NOT NULL;
