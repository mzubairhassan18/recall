# Relay API

The relay is deliberately dumb. It stores opaques blobs and a small metadata row per blob.
It has no search, no OCR, no indexing, no plaintext, no push.

Base URL: `https://recall-relay.mzubairhassan18.workers.dev` (the deployed Worker origin).

## Authentication

Every request sends:

| Header | Value |
| --- | --- |
| `X-Relay-Secret` | shared secret from the Cloudflare env var `RELAY_SECRET` |
| `X-User-Id` | UUID returned by `/auth/register`, stored locally per device |

`/health` is the only unauthenticated endpoint. A missing/invalid secret is `401`; a missing
`X-User-Id` on a data endpoint is `401`.

## Endpoints

### `POST /auth/register`

```json
{ "username": "u_ab12..." }
```

Idempotent by `username`. Creates the user on first call.

Response `200`:

```json
{ "user_id": "<uuid>", "salt": "<base64, 16 bytes>" }
```

The salt is plaintext by design. A client that already has a `user_id` + salt must keep using
them; registering the same username returns the same pair.

### `PUT /blob/:id`

Body: the envelope JSON from `docs/wire-format.md` (max 5 MB).

Response `204`. Re-uploading the same `id` for the same user overwrites (idempotent).

`id` must match `^[A-Za-z0-9_-]{8,128}$`. `created_at` is taken from the request header
`X-Created-At` (unix ms) when present, else the server clock.

### `GET /blob/:id`

Response `200` with the stored envelope JSON. `404` if it does not exist for this user.

### `GET /list?since=<unix_ms>`

Response `200`:

```json
[{ "id": "...", "created_at": 1712345678901, "size": 412 }]
```

Only the caller's blobs; `since` is exclusive (`created_at > since`). Sorted ascending by
`created_at`, then `id`. Without `since`, returns everything (capped at 5000 rows).

### `GET /health`

`200 {"ok":true}`. No auth.

## Storage

- D1 `blobs(id TEXT PRIMARY KEY, user_id TEXT, created_at INTEGER, size INTEGER)`, index on
  `(user_id, created_at)`.
- D1 `users(user_id TEXT PRIMARY KEY, username TEXT UNIQUE, salt TEXT, created_at INTEGER)`.
- R2 object key: `<user_id>/<id>`. Never a display title, URL, or plaintext.

## Errors

`{"error":"<code>"}` with `400` (bad input), `401` (auth), `404` (missing), `413` (too large),
`500` (unexpected). Errors never echo request bodies or secrets.
