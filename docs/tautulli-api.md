# Tautulli API

Miru can answer the part of [Tautulli's API](https://docs.tautulli.com/extending-tautulli/api-reference) that [Kometa](https://kometa.wiki) uses, so that Kometa and similar tools can read from Miru instead of Tautulli. It is off by default.

- [Setup](#setup)
- [Commands](#commands)
- [Differences from Tautulli](#differences-from-tautulli)

## Setup

1. Set `ENABLE_TAUTULLI_COMPAT_API=true` and restart Miru.
2. Create an API key with the role `ADMIN` on the Admin page. Keys with the role `USER` are refused, because tools take the answers to be about everyone.
3. In Kometa's `config.yml`, use Miru's URL and the key:

   ```yaml
   tautulli:
     url: http://miru:8080
     apikey: miru_...
   ```

The builders `tautulli_popular`, `tautulli_watched` and `tautulli_top` then work as before. This is tested with Kometa 2.5.5.

Tools send the key in the URL. Miru does not log it, but reverse proxies usually do, so use an address that reaches Miru directly. The key is also accepted in the `X-Api-Key` header.

The shows of episodes and the artists of tracks come from Miru's library scan. After an upgrade from a version without this API, Miru scans the libraries once when it starts; until that is done, shows and artists are missing from the statistics.

## Commands

`GET /api/v2?apikey=<key>&cmd=<command>`, with answers like `{"response": {"result": "success", "message": null, "data": …}}`.

| Command | Parameters |
| --- | --- |
| `get_home_stats` | `time_range` (days, default 30), `stats_count` (default 10, up to 10,000), `stats_start`, `stats_type` (`plays` or `duration`), `stat_id`, `section_id`, `user_id` |
| `get_history` | `section_id`, `user_id`, `rating_key`, `media_type`, `after`, `before`, `order_column` (`date`, `started`, `stopped`, `duration` or `full_title`), `order_dir`, `start`, `length` (default 25, up to 1,000), `draw` |
| `get_tautulli_info` | |
| `status` | |
| `arnold` | |

- `stat_id` is `top_movies`, `popular_movies`, `top_tv`, `popular_tv`, `top_music` or `popular_music`. Without it, all of them are answered.
- `user_id` is the ID of a Plex account. `user_id`, and in `get_history` also `section_id`, `rating_key` and `media_type`, can be several values separated by commas.
- `get_tautulli_info` answers the version of Tautulli that this follows, 2.19.1.

## Differences from Tautulli

- **Refused parameters:** a filter that Tautulli has and Miru does not, such as `grandparent_rating_key`, `user` or `search`, is an error (400). Without it, the answer would be about more plays than the tool asked for.
- **Media server:** answers are about the media server Miru is connected to.
- **Plays:** Miru keeps a resumed play as one, so `grouping` has no effect and `group_count` is always 1.
- **Shows and artists:** plays of those that are no longer in the library are left out of `get_home_stats`, as there is no rating key to report them with. So are episodes that are gone if several shows in the library have their title.
- **Live TV** is only in the history.
- **Dates** are in UTC.
- **Empty fields:** what Miru does not know, such as `art`, `guid`, `labels`, `parent_rating_key` and `originally_available_at`, is there but empty. The IDs of plays (`row_id`, `reference_id`) are text. `get_history` has no `filter_duration` and `total_duration`.
