-- AUTO: every SSO sign-in sets the account's role from its groups.
-- MANUAL: the role is exactly what an admin set and is never changed by sign-in.
ALTER TABLE auth_accounts ADD COLUMN role_mode TEXT NOT NULL DEFAULT 'MANUAL';

-- Until now every sign-in set the role of an SSO account, so none of them kept a role an admin set.
UPDATE auth_accounts
SET role_mode = 'AUTO'
WHERE auth_provider = 'OIDC';
