# Webhooks

Miru can send events to HTTP endpoints, for example to post in a chat when a stream starts transcoding, or to run an automation when the media server goes down.

What is described here is part of the [compatibility promise](compatibility.md): ignore fields and events you do not know.

- [Setting up a target](#setting-up-a-target)
- [Receiving events](#receiving-events)
- [Events](#events)
- [Payload](#payload)
- [Troubleshooting](#troubleshooting)

## Setting up a target

Admins add targets on the Admin page under **Outbound Webhooks & Delivery Log**, or with `/api/v1/admin/webhooks`. **Send Test** sends a `webhook.test` event right away and shows the result.

| Setting | API field | Default | Allowed |
| --- | --- | --- | --- |
| Name | `name` | | 1 to 100 characters |
| URL | `url` | | `http` or `https`, up to 2,048 characters, without a username or password |
| Events | `subscribedEvents` | | At least one of the [events](#events) |
| Enabled | `enabled` | `true` | |
| Cooldown, in seconds | `cooldownSeconds` | `60` | 0 to 86,400 |
| Concurrent streams | `concurrentStreamThreshold` | `3` | 2 to 50 |
| Buffering count | `bufferingSpikeThreshold` | `3` | 2 to 20 |
| Buffering window, in seconds | `bufferingWindowSeconds` | `120` | 10 to 3,600 |
| Transcodes of 4K only | `transcode4kOnly` | `false` | |
| Signing secret | `signingSecret` | | Up to 512 characters |
| Authorization header | `authHeaderName`, `authHeaderValue` | | Name up to 128 characters, value up to 2,048 |

- Storing a signing secret or an authorization header needs `MIRU_ENCRYPTION_KEY`. Both are encrypted, and neither is ever returned by the API.
- Query parameters of the URL are hidden once it is saved.
- The URL may point to the local network. Cloud metadata services and link-local, multicast and unspecified addresses are refused.
- The authorization header cannot replace the headers Miru sets itself.

A target does not get the same event again within its cooldown: for the same stream, for the same user with `stream.concurrent_limit`, and for the same server with the `server.*` events.

## Receiving events

Each delivery is a `POST` with a JSON body and these headers:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json; charset=utf-8` |
| `User-Agent` | `Miru/<version>` |
| `X-Miru-Event` | The event, the same as `event` in the body. |
| `X-Miru-Delivery` | ID of the delivery, as in the delivery log. Retries of a delivery keep it, so it can be used to spot repeats. |
| `X-Miru-Signature-256` | The signature, if the target has a signing secret. |

Answer with any `2xx` status, and do slow work afterwards.

### Verifying the signature

`X-Miru-Signature-256` is `sha256=` followed by the HMAC-SHA256 of the raw request body, keyed with the signing secret, in lowercase hexadecimal:

```
X-Miru-Signature-256: sha256=f4a3c7502660171ad2170df1a0360d8da6e579a10084c4f49a8845531d04fe64
```

This is the signature of the body `{"event":"webhook.test"}` with the secret `secret`, to try an implementation with.

The prefix names the algorithm and is part of the value, as in GitHub's `X-Hub-Signature-256`. Include it when comparing, or remove it first. Use the body as it was received, before parsing it, and compare in constant time.

Python:

```python
import hashlib, hmac

def is_from_miru(secret: str, body: bytes, header: str) -> bool:
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)
```

Node.js:

```js
import { createHmac, timingSafeEqual } from 'node:crypto';

function isFromMiru(secret, body, header) {
  const expected = Buffer.from('sha256=' + createHmac('sha256', secret).update(body).digest('hex'));
  const actual = Buffer.from(header ?? '');
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
```

Shell, to check a saved body:

```sh
openssl dgst -sha256 -hmac "$SECRET" -r body.json | cut -d' ' -f1
```

### Retries

| Result | What happens |
| --- | --- |
| `2xx` | Delivered |
| Network error, timeout, `408`, `429`, `5xx` | Tried twice more, after 2 and then 6 seconds |
| Any other status | Failed at once. Redirects are not followed |

The timeout is 5 seconds to connect and 10 seconds in total. Test deliveries are not retried.

Up to four deliveries are sent at a time, including the ones that wait for a retry, and up to 128 more wait in memory. Events are dropped when that is full, waiting ones are lost on a restart, and the order is not guaranteed.

## Events

| Event | Sent when | `details` |
| --- | --- | --- |
| `transcode.started` | A stream starts transcoding video. | `hwTranscode`, `is4k`, `videoResolution`, `videoCodec`, `transcodeVideoCodec` |
| `stream.concurrent_limit` | A user has more streams than the target's threshold. | `activeStreamCount`, `threshold` |
| `stream.buffering_spike` | A stream started buffering as often as the target's threshold within its window. | `bufferingCount`, `threshold`, `windowSeconds` |
| `stream.terminated` | Miru stopped a stream, for an admin or a guardrail. | `reason`, `source`, `actorName`, `ruleType` |
| `server.unreachable` | Three checks of the media server in a row failed. | `consecutiveFailures`, `reason` |
| `server.recovered` | The media server answers again after `server.unreachable`. | `outageDurationSec` |
| `webhook.test` | An admin sends a test. It cannot be subscribed to. | `message` |

### Fields of `details`

| Field | Type | Description |
| --- | --- | --- |
| `hwTranscode` | boolean | Whether the transcode is hardware-accelerated. |
| `is4k` | boolean | Whether the source is 4K or higher. |
| `videoResolution` | string | Resolution of the source, if known. |
| `videoCodec` | string | Codec of the source, if known. |
| `transcodeVideoCodec` | string | Codec it is transcoded to, if known. |
| `activeStreamCount` | integer | Streams the user has now. |
| `threshold` | integer | The target's threshold for this event. |
| `bufferingCount` | integer | How often the stream started buffering within the window. |
| `windowSeconds` | integer | The target's window. |
| `reason` | string | Message shown to the viewer, or why the server could not be reached. |
| `source` | string | `MANUAL` or `GUARDRAIL`. |
| `actorName` | string | Username of the admin, or the name of the guardrail. |
| `ruleType` | string | `TRANSCODE_4K`, `PAUSED_TIMEOUT` or `CONCURRENT_STREAMS`. Only for guardrails. |
| `consecutiveFailures` | integer | Failed checks so far. |
| `outageDurationSec` | integer | How long the server was unreachable, in seconds. |
| `message` | string | Text of the test. |

## Payload

| Field | Type | Description |
| --- | --- | --- |
| `schemaVersion` | integer | `1`. It only changes with a major release. |
| `event` | string | One of the events above. |
| `eventId` | string | ID of the event for this target. |
| `timestamp` | string | When it happened, in UTC (ISO 8601). |
| `server` | object | The media server, if known. |
| `user` | object | The user, for events about a stream, if known. |
| `session` | object | The stream, for events about one. |
| `details` | object | What is specific to the event. Always there, possibly empty. |

Fields without a value are left out rather than sent as `null`.

### Fields of `server` and `user`

| Field | Type | Description |
| --- | --- | --- |
| `id` | string | ID in Miru. |
| `name` | string | Name of the server, if known. |
| `username` | string | Username. |
| `displayName` | string | Display name, if there is one. |

### Fields of `session`

| Field | Type | Description |
| --- | --- | --- |
| `sessionKey` | string | Key of the stream. Always there. |
| `sessionId` | string | ID of the Plex session. |
| `state` | string | `playing`, `paused` or `buffering`. Always there. |
| `mediaType` | string | `movie`, `episode`, `track`, `clip`, `live_tv` or another type. Always there. |
| `title` | string | Title of the movie, episode or track. Always there. |
| `grandparentTitle` | string | Title of the show, or name of the artist. |
| `streamDecision` | string | `direct_play`, `direct_stream` or `transcode`. Always there. |
| `videoDecision` | string | The same for the video alone. |
| `audioDecision` | string | The same for the audio alone. |
| `videoResolution` | string | Resolution of the source. |
| `videoCodec` | string | Codec of the source. |
| `transcodeVideoCodec` | string | Codec it is transcoded to. |
| `hwTranscode` | boolean | Whether hardware transcoding is used. Always there. |
| `playerName` | string | Name of the player. |
| `playerPlatform` | string | Platform of the player. |
| `bandwidthKbps` | integer | Bandwidth of the stream. |

### Examples

A stream stopped by a guardrail:

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

The media server is back after five minutes:

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

## Troubleshooting

The delivery log on the Admin page, and `GET /api/v1/admin/webhooks/deliveries`, keep the last 1,000 deliveries with their status (`DELIVERED`, `RETRYING` or `FAILED`), attempts, HTTP status and error.

| Problem | Likely cause |
| --- | --- |
| Nothing in the log | The target is disabled or not subscribed to the event, the threshold was not reached, or the event fell within the cooldown. |
| The signature does not match | The body was parsed and written again before checking, the `sha256=` prefix was left out on one side, or the secret differs. |
| No `X-Miru-Signature-256` header | The target has no signing secret. |
| `redirects are disabled` | The URL answers with a redirect. Use the one it points to. |
| `host is blocked for SSRF protection`, or a blocked IP range | The URL points to a refused address. |
| A secret or header cannot be saved | `MIRU_ENCRYPTION_KEY` is not set, or is shorter than 32 characters. |
| Deliveries fail after the key changed | Stored secrets can only be read with the key they were saved with. Enter them again. |
