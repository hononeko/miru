-- Enforce single-account ownership per media_user_id.
-- If legacy rows linked the same media_user_id to multiple accounts, deterministically keep one owner:
-- prefer MANUAL link mode over AUTO, then earliest created_at_ms, then lowest account_id.
DELETE FROM auth_account_media_users
WHERE EXISTS (
    SELECT 1
    FROM auth_account_media_users AS winner
    JOIN auth_accounts AS a_cur ON a_cur.id = auth_account_media_users.account_id
    JOIN auth_accounts AS a_win ON a_win.id = winner.account_id
    WHERE winner.media_user_id = auth_account_media_users.media_user_id
      AND (
          (CASE WHEN a_win.media_user_link_mode = 'MANUAL' THEN 0 ELSE 1 END)
              < (CASE WHEN a_cur.media_user_link_mode = 'MANUAL' THEN 0 ELSE 1 END)
          OR (
              (CASE WHEN a_win.media_user_link_mode = 'MANUAL' THEN 0 ELSE 1 END)
                  = (CASE WHEN a_cur.media_user_link_mode = 'MANUAL' THEN 0 ELSE 1 END)
              AND (
                  winner.created_at_ms < auth_account_media_users.created_at_ms
                  OR (
                      winner.created_at_ms = auth_account_media_users.created_at_ms
                      AND winner.account_id < auth_account_media_users.account_id
                  )
              )
          )
      )
);

DROP INDEX IF EXISTS idx_auth_account_media_users_media_user_id;
CREATE UNIQUE INDEX uq_auth_account_media_users_media_user_id ON auth_account_media_users (media_user_id);
