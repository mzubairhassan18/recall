import type { AuthRegisterResponse, SyncItem } from "@recall/shared-types";

/**
 * Recall relay — Cloudflare Worker + R2 + D1.
 *
 * Stores only opaque envelope JSON plus {id, user_id, created_at, size}. It never sees a
 * title, URL, OCR text, thumbnail, passphrase, or plaintext payload. No search. No OCR.
 */

export interface Env {
  DB: D1Database;
  BLOBS: R2Bucket;
  RELAY_SECRET: string;
}

const MAX_BLOB_BYTES = 5 * 1024 * 1024;
const LIST_LIMIT = 5000;
const ID_PATTERN = /^[A-Za-z0-9_-]{8,128}$/;
const USERNAME_PATTERN = /^[A-Za-z0-9_.:-]{3,64}$/;

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,PUT,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type,X-Relay-Secret,X-User-Id,X-Created-At",
  "Access-Control-Max-Age": "86400",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    try {
      if (url.pathname === "/health" && request.method === "GET") {
        return json({ ok: true }, 200);
      }

      if (!hasValidSecret(request, env)) return error("unauthorized", 401);

      if (url.pathname === "/auth/register" && request.method === "POST") {
        return await register(request, env);
      }

      if (url.pathname.startsWith("/blob/")) {
        const id = url.pathname.slice("/blob/".length);
        if (!ID_PATTERN.test(id)) return error("bad-id", 400);
        const userId = request.headers.get("X-User-Id");
        if (!userId || !ID_PATTERN.test(userId)) return error("unauthorized", 401);

        if (request.method === "PUT") return await putBlob(request, env, userId, id);
        if (request.method === "GET") return await getBlob(env, userId, id);
        return error("method-not-allowed", 405);
      }

      if (url.pathname === "/list" && request.method === "GET") {
        const userId = request.headers.get("X-User-Id");
        if (!userId || !ID_PATTERN.test(userId)) return error("unauthorized", 401);
        return await listBlobs(url, env, userId);
      }

      return error("not-found", 404);
    } catch {
      // Never include request details or payloads in the error path.
      return error("internal", 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function register(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{ username?: unknown }>(request);
  const username = body?.username;
  if (typeof username !== "string" || !USERNAME_PATTERN.test(username)) {
    return error("bad-username", 400);
  }

  const existing = await env.DB.prepare("SELECT user_id, salt FROM users WHERE username = ?")
    .bind(username)
    .first<{ user_id: string; salt: string }>();
  if (existing) {
    return json({ user_id: existing.user_id, salt: existing.salt } satisfies AuthRegisterResponse, 200);
  }

  const userId = crypto.randomUUID();
  const salt = base64(randomBytes(16));
  await env.DB.prepare("INSERT INTO users (user_id, username, salt, created_at) VALUES (?, ?, ?, ?)")
    .bind(userId, username, salt, Date.now())
    .run();

  return json({ user_id: userId, salt } satisfies AuthRegisterResponse, 200);
}

async function putBlob(request: Request, env: Env, userId: string, id: string): Promise<Response> {
  const declaredLength = Number(request.headers.get("Content-Length") ?? "0");
  if (declaredLength > MAX_BLOB_BYTES) return error("too-large", 413);

  const text = await request.text();
  if (text.length > MAX_BLOB_BYTES) return error("too-large", 413);
  if (!isValidEnvelope(text)) return error("bad-envelope", 400);

  const createdAt = parseCreatedAt(request.headers.get("X-Created-At"));
  if (createdAt === null) return error("bad-created-at", 400);

  await env.BLOBS.put(`${userId}/${id}`, text, {
    httpMetadata: { contentType: "application/json" },
  });
  await env.DB.prepare(
    `INSERT INTO blobs (id, user_id, created_at, size) VALUES (?, ?, ?, ?)
     ON CONFLICT(user_id, id) DO UPDATE SET created_at = excluded.created_at, size = excluded.size`,
  )
    .bind(id, userId, createdAt, text.length)
    .run();

  return new Response(null, { status: 204, headers: CORS_HEADERS });
}

async function getBlob(env: Env, userId: string, id: string): Promise<Response> {
  const object = await env.BLOBS.get(`${userId}/${id}`);
  if (!object) return error("not-found", 404);
  const body = await object.text();
  return new Response(body, {
    status: 200,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

async function listBlobs(url: URL, env: Env, userId: string): Promise<Response> {
  const rawSince = url.searchParams.get("since") ?? "0";
  const since = Number(rawSince);
  if (!Number.isFinite(since) || since < 0) return error("bad-since", 400);

  const rows = await env.DB.prepare(
    `SELECT id, created_at, size FROM blobs
     WHERE user_id = ? AND created_at > ?
     ORDER BY created_at ASC, id ASC
     LIMIT ?`,
  )
    .bind(userId, Math.floor(since), LIST_LIMIT)
    .all<SyncItem>();

  return json(rows.results ?? [], 200);
}

function hasValidSecret(request: Request, env: Env): boolean {
  if (typeof env.RELAY_SECRET !== "string" || env.RELAY_SECRET.length === 0) return false;
  const provided = request.headers.get("X-Relay-Secret");
  if (!provided) return false;
  return constantTimeEqual(provided, env.RELAY_SECRET);
}

function constantTimeEqual(a: string, b: string): boolean {
  const encoder = new TextEncoder();
  const left = encoder.encode(a);
  const right = encoder.encode(b);
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) diff |= (left[i] as number) ^ (right[i] as number);
  return diff === 0;
}

/** Structural validation only. The relay never attempts to decrypt or parse the payload. */
function isValidEnvelope(text: string): boolean {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return false;
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record);
  if (keys.length !== 3 || !keys.includes("v") || !keys.includes("iv") || !keys.includes("ct")) {
    return false;
  }
  if (record.v !== 1 || typeof record.iv !== "string" || typeof record.ct !== "string") return false;
  if (!isBase64OfLength(record.iv, 12)) return false;
  const ct = decodeBase64(record.ct);
  return ct !== null && ct.length >= 16;
}

function parseCreatedAt(header: string | null): number | null {
  if (header === null) return Date.now();
  const value = Number(header);
  if (!Number.isFinite(value) || value <= 0) return null;
  if (value > Date.now() + 86_400_000) return null;
  return Math.floor(value);
}

function isBase64OfLength(value: string, length: number): boolean {
  const decoded = decodeBase64(value);
  return decoded !== null && decoded.length === length;
}

function decodeBase64(value: string): Uint8Array | null {
  if (typeof value !== "string" || value.length === 0 || value.length % 4 !== 0) return null;
  try {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i] as number);
  return btoa(binary);
}

function randomBytes(length: number): Uint8Array {
  const out = new Uint8Array(length);
  crypto.getRandomValues(out);
  return out;
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

function json(value: unknown, status: number): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function error(code: string, status: number): Response {
  return json({ error: code }, status);
}
