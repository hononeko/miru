-- AUTO: Miru keeps the account's linked media users equal to the media users that share the
-- account's verified SSO email, refreshed on every sign-in.
-- MANUAL: the links are exactly what an admin set and are never changed by sign-in.
ALTER TABLE auth_accounts ADD COLUMN media_user_link_mode TEXT NOT NULL DEFAULT 'MANUAL';

-- Existing SSO accounts whose links are all email matches (or that have no links yet) were
-- linked automatically, so they stay AUTO. Any other SSO account has links an admin set by
-- hand, which must not be overwritten.
UPDATE auth_accounts
SET media_user_link_mode = 'AUTO'
WHERE auth_provider = 'OIDC'
  AND NOT EXISTS (
      SELECT 1
      FROM auth_account_media_users links
      JOIN users ON users.id = links.media_user_id
      WHERE links.account_id = auth_accounts.id
        AND (
            auth_accounts.email IS NULL
            OR users.email IS NULL
            OR LOWER(users.email) <> LOWER(auth_accounts.email)
        )
  );
