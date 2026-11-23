-- AUTO: Miru keeps the account's linked media users equal to the media users that share the
-- account's verified SSO email, refreshed on every sign-in.
-- MANUAL: the links are exactly what an admin set and are never changed by sign-in.
ALTER TABLE auth_accounts ADD COLUMN media_user_link_mode TEXT NOT NULL DEFAULT 'MANUAL';

-- Existing SSO accounts were linked automatically if they have no links yet, or if their links are
-- exactly the media users that share their email. Any other SSO account has links an admin set by hand
-- (an extra link, or a removed email match), which must not be overwritten.
UPDATE auth_accounts
SET media_user_link_mode = 'AUTO'
WHERE auth_provider = 'OIDC'
  -- Every link is an email match.
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
  )
  -- And, if there are links, every email match is linked.
  AND (
      NOT EXISTS (
          SELECT 1 FROM auth_account_media_users links WHERE links.account_id = auth_accounts.id
      )
      OR NOT EXISTS (
          SELECT 1
          FROM users
          WHERE LOWER(users.email) = LOWER(auth_accounts.email)
            AND NOT EXISTS (
                SELECT 1
                FROM auth_account_media_users links
                WHERE links.account_id = auth_accounts.id
                  AND links.media_user_id = users.id
            )
      )
  );
