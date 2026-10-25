#!/usr/bin/env node
/**
 * Relay smoke test: local Worker (wrangler dev --local) + R2 + D1.
 * Upload -> list -> download -> decrypt round-trip, plus auth/validation failures.
 * A throwaway secret and key are generated at runtime; nothing is committed or logged.
 */
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from "node:crypto";
import { existsSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const RELAY_DIR = path.join(ROOT, "apps", "relay");
const WRANGLER_BIN = path.join(RELAY_DIR, "node_modules", "wrangler", "bin", "wrangler.js");
const DEV_VARS = path.join(RELAY_DIR, ".dev.vars");
const PORT = Number(process.env.RELAY_SMOKE_PORT ?? 8787);
const BASE = process.env.RELAY_URL ?? `http://127.0.0.1:${PORT}`;
const SECRET = randomBytes(24).toString("base64url");
const KEY = randomBytes(32);
const USERNAME = `smoke_${randomUUID().slice(0, 8)}`;

const children = [];

function runWrangler(args) {
  const result = spawnSync(process.execPath, [WRANGLER_BIN, ...args], {
    cwd: RELAY_DIR,
    stdio: "pipe",
    env: process.env,
    encoding: "utf8",
  });
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || "").trim().split("\n").slice(-8).join("\n");
    throw new Error(`wrangler ${args.join(" ")} failed (${result.status})\n${detail}`);
  }
  return result.stdout ?? "";
}

function startDev() {
  if (!existsSync(WRANGLER_BIN)) throw new Error("wrangler is not installed; run pnpm install");
  // Local-only secret, generated per run and deleted in the finally block.
  writeFileSync(DEV_VARS, `RELAY_SECRET=${SECRET}\n`, "utf8");
  const child = spawn(
    process.execPath,
    [WRANGLER_BIN, "dev", "--local", "--port", String(PORT)],
    {
      cwd: RELAY_DIR,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  children.push(child);
  child.stdout.on("data", () => {});
  child.stderr.on("data", () => {});
  return child;
}

async function waitForHealth(timeoutMs = 120_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const response = await fetch(`${BASE}/health`);
      if (response.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("relay did not become healthy");
}

function envelope(payload) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", KEY, iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(payload), "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return { v: 1, iv: iv.toString("base64"), ct: Buffer.concat([ciphertext, tag]).toString("base64") };
}

function openEnvelope(value) {
  const iv = Buffer.from(value.iv, "base64");
  const combined = Buffer.from(value.ct, "base64");
  const decipher = createDecipheriv("aes-256-gcm", KEY, iv);
  decipher.setAuthTag(combined.subarray(combined.length - 16));
  const plaintext = Buffer.concat([
    decipher.update(combined.subarray(0, combined.length - 16)),
    decipher.final(),
  ]);
  return JSON.parse(plaintext.toString("utf8"));
}

async function request(pathname, { method = "GET", headers = {}, body } = {}) {
  const response = await fetch(`${BASE}${pathname}`, {
    method,
    headers: {
      "X-Relay-Secret": SECRET,
      ...headers,
    },
    body,
  });
  return response;
}

function authHeaders(userId) {
  return { "X-User-Id": userId, "Content-Type": "application/json" };
}

async function main() {
  if (!process.env.RELAY_URL) {
    console.log("applying local D1 migrations…");
    runWrangler(["d1", "migrations", "apply", "recall-meta", "--local"]);
    console.log(`starting local relay on ${BASE}…`);
    startDev();
    await waitForHealth();
  }

  const health = await fetch(`${BASE}/health`);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { ok: true });

  const unauthorized = await fetch(`${BASE}/list`);
  assert.equal(unauthorized.status, 401);

  const wrongSecret = await fetch(`${BASE}/list`, {
    headers: { "X-Relay-Secret": "nope", "X-User-Id": randomUUID() },
  });
  assert.equal(wrongSecret.status, 401);

  const register = await request("/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: USERNAME }),
  });
  assert.equal(register.status, 200);
  const identity = await register.json();
  assert.match(identity.user_id, /^[0-9a-f-]{36}$/);
  assert.equal(Buffer.from(identity.salt, "base64").length, 16);

  const again = await request("/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: USERNAME }),
  });
  assert.deepEqual(await again.json(), identity, "register must be idempotent by username");

  const ids = [randomUUID(), randomUUID(), randomUUID()];
  const createdAt = [1_700_000_000_000, 1_700_000_001_000, 1_700_000_002_000];
  const payloads = [
    { type: "text", text: "smoke one" },
    { type: "url", url: "https://example.com/smoke", title: "smoke two" },
    { type: "screenshot", ocr_text: "smoke three", thumbnail_b64: "AAAA" },
  ];

  for (let index = 0; index < ids.length; index += 1) {
    const body = JSON.stringify(envelope(payloads[index]));
    const put = await request(`/blob/${ids[index]}`, {
      method: "PUT",
      headers: { ...authHeaders(identity.user_id), "X-Created-At": String(createdAt[index]) },
      body,
    });
    assert.equal(put.status, 204, `PUT ${ids[index]} -> ${put.status}`);
  }

  const missingUser = await request("/list", { headers: { "X-Relay-Secret": SECRET } });
  assert.equal(missingUser.status, 401);

  const list = await request(`/list?since=0`, { headers: { "X-User-Id": identity.user_id } });
  assert.equal(list.status, 200);
  const items = await list.json();
  assert.equal(items.length, 3);
  assert.deepEqual(items.map((item) => item.id), ids);
  assert.deepEqual(items.map((item) => item.created_at), createdAt);
  for (const item of items) assert.ok(item.size > 0);

  const since = await request(`/list?since=${createdAt[1]}`, {
    headers: { "X-User-Id": identity.user_id },
  });
  assert.deepEqual((await since.json()).map((item) => item.id), [ids[2]]);

  for (let index = 0; index < ids.length; index += 1) {
    const blob = await request(`/blob/${ids[index]}`, {
      headers: { "X-User-Id": identity.user_id },
    });
    assert.equal(blob.status, 200);
    const downloaded = await blob.json();
    assert.deepEqual(openEnvelope(downloaded), payloads[index]);
  }

  const crossUser = await request(`/blob/${ids[0]}`, {
    headers: { "X-User-Id": randomUUID() },
  });
  assert.equal(crossUser.status, 404, "blobs must be scoped per user");

  const badEnvelope = await request(`/blob/${randomUUID()}`, {
    method: "PUT",
    headers: authHeaders(identity.user_id),
    body: JSON.stringify({ v: 1, iv: "not-base64", ct: "also-not" }),
  });
  assert.equal(badEnvelope.status, 400);

  const badId = await request(`/blob/short`, {
    method: "PUT",
    headers: authHeaders(identity.user_id),
    body: "{}",
  });
  assert.equal(badId.status, 400);

  console.log("relay-smoke: PASS (health, auth, register, put x3, list, since, get, decrypt, scoping, validation)");
}

try {
  await main();
} finally {
  for (const child of children) {
    if (!child.pid) continue;
    if (process.platform === "win32") {
      spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
    } else {
      child.kill("SIGTERM");
    }
  }
  if (existsSync(DEV_VARS)) rmSync(DEV_VARS, { force: true });
}
