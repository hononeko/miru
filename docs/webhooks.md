# Webhooks

Miru can send events to HTTP endpoints, for example a chat notifier or an automation. Admins set targets up on the Admin page under **Outbound Webhooks** or with `/api/v1/admin/webhooks`. Storing a signing secret or an authorization header needs `MIRU_ENCRYPTION_KEY`.

What is described here is part of the [compatibility promise](compatibility.md): ignore fields and events you do not know.

## Request

Each delivery is a `POST` with a JSON body and these headers:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json; charset=utf-8` |
| `User-Agent` | `Miru/<version>` |
| `X-Miru-Event` | The event, the same as `event` in the body. |
| `X-Miru-Delivery` | ID of the delivery, as in the delivery log. Retries of a delivery keep it, so it can be used to spot repeats. |
| `X-Miru-Signature-256` | `sha256=` and the signature, if the target has a signing secret. |

A target can also have one authorization header of its own.

### Verifying the signature

The signature is the HMAC-SHA256 of the raw request body, keyed with the signing secret, in lowercase hexadecimal. Compute it before parsing the body, and compare in constant time:

```python
import hashlib, hmac

def is_from_miru(secret: str, body: bytes, header: str) -> bool:
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)
```

### Response and retries

Any `2xx` status counts as delivered. Redirects are not followed.

After a network error, a timeout (5 seconds to connect, 10 seconds in total), `408`, `429` or `5xx`, Miru tries twice more, after 2 and then 6 seconds. Any other status fails at once. Test deliveries are not retried.

Up to four deliveries are sent at a time, and the rest wait in memory. Waiting ones are lost on a restart, events can be dropped during a burst, and the order is not guaranteed.

## Events

| Event | Sent when | `details` |
| --- | --- | --- |
| `transcode.started` | A stream starts transcoding video. A target can be limited to 4K sources. | `hwTranscode`, `is4k`, `videoResolution`, `videoCodec`, `transcodeVideoCodec` |
| `stream.concurrent_limit` | A user has more streams than the target's threshold. | `activeStreamCount`, `threshold` |
| `stream.buffering_spike` | A stream started buffering as often as the target's threshold within its time window. | `bufferingCount`, `threshold`, `windowSeconds` |
| `stream.terminated` | Miru stopped a stream, for an admin or a guardrail. | `reason`, `source`, `actorName`, `ruleType` |
| `server.unreachable` | Three checks of the media server in a row failed. | `consecutiveFailures`, `reason` |
| `server.recovered` | The media server answers again after `server.unreachable`. | `outageDurationSec` |
| `webhook.test` | An admin sends a test. It cannot be subscribed to. | `message` |

In `details` of `stream.terminated`, `source` is `MANUAL` or `GUARDRAIL`, and `ruleType` is only there for guardrails: `TRANSCODE_4K`, `PAUSED_TIMEOUT` or `CONCURRENT_STREAMS`.

A target does not get the same event again within its cooldown: for the same stream, for the same user with `stream.concurrent_limit`, and for the same server with the `server.*` events.

## Payload

```json
{
  "schemaVersion": 1,
  "event": "stream.terminated",
  "eventId": "5c0b0e62-1a52-4c52-9a30-5f0f6b7d7f0e",
  "timestamp": "2026-11-25T18:04:05Z",
  "server": { "id": "srv-1", "name": "Plex" },
  "user": { "id": "u-1", "username": "alice", "displayName": "Alice" },
  "session": {
    "sessionKey": "42",
    "sessionId": "x8f3k2",
    "state": "playing",
    "mediaType": "episode",
    "title": "Half Loop",
    "grandparentTitle": "Severance",
    "streamDecision": "transcode",
    "videoDecision": "transcode",
    "audioDecision": "direct_stream",
    "videoResolution": "4k",
    "videoCodec": "hevc",
    "transcodeVideoCodec": "h264",
    "hwTranscode": true,
    "playerName": "Living Room",
    "playerPlatform": "tvOS",
    "bandwidthKbps": 18000
  },
  "details": {
    "reason": "4K video transcoding is disabled on this server.",
    "source": "GUARDRAIL",
    "actorName": "Guardrail: 4K Transcode",
    "ruleType": "TRANSCODE_4K"
  }
}
```

```json
{
  "schemaVersion": 1,
  "event": "server.recovered",
  "eventId": "0d6a5b1e-7f0c-4b0b-8f53-3b1f5d1c9a77",
  "timestamp": "2026-11-25T18:09:05Z",
  "server": { "id": "srv-1", "name": "Plex" },
  "details": { "outageDurationSec": 300 }
}
```

| Field | Description |
| --- | --- |
| `schemaVersion` | `1`. It only changes with a major release. |
| `event` | One of the events above. |
| `eventId` | ID of the event for this target. |
| `timestamp` | When it happened, in UTC (ISO 8601). |
| `server` | `id` and `name` of the media server, if known. |
| `user` | `id`, `username` and `displayName` of the user, for events about a stream, if known. |
| `session` | The stream, for events about one. The fields are those of the example. |
| `details` | What is specific to the event, as listed above. Always there, possibly empty. |

Fields without a value are left out rather than sent as `null`. In `session`, only `sessionKey`, `state`, `mediaType`, `title`, `streamDecision` and `hwTranscode` are always there.
