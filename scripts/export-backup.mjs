#!/usr/bin/env node
/**
 * Encrypted backup tool.
 *
 * The backup format matches the clients: a zip with `manifest.json` (ids + timestamps only)
 * and one `<id>.json` envelope per capture. Nothing in the zip is plaintext, so the passphrase
 * is only needed for `--verify` (and for `--demo`, which creates test data).
 *
 * Examples:
 *   RECALL_PASSPHRASE='…' pnpm export:backup --demo --out recall-demo.zip
 *   RECALL_PASSPHRASE='…' pnpm export:backup --input recall-demo.zip --verify
 *   RELAY_SECRET='…' pnpm export:backup --relay https://… --user-id <uuid> --out relay.zip
 *   RELAY_SECRET='…' pnpm export:backup --input relay.zip --relay https://… --user-id <uuid> --upload
 *
 * Secrets are read from the environment or a file, never from argv, and are never printed.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { inflateRawSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import path from "node:path";
import assert from "node:assert/strict";
import {
  canonicalPayloadJson,
  decrypt,
  deriveKeyBytes,
  encrypt,
  envelopeToJson,
  parseEnvelope,
} from "@recall/crypto-core";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const args = process.argv.slice(2);

const flag = (name) => args.includes(name);
const option = (name, fallback = null) => {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  if (index + 1 >= args.length) throw new Error(`missing value for ${name}`);
  return args[index + 1];
};

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

function getPassphrase() {
  const fromEnv = process.env.RECALL_PASSPHRASE;
  if (fromEnv) return fromEnv;
  const file = option("--passphrase-file");
  if (file) return readFileSync(file, "utf8").replace(/\r?\n$/, "");
  fail("set RECALL_PASSPHRASE or pass --passphrase-file (argv is not accepted)");
}

function getRelaySecret() {
  const secret = process.env.RELAY_SECRET;
  if (!secret) fail("set RELAY_SECRET in the environment");
  return secret;
}

/* ---------------------------------------------------------------------------------------------
 * Minimal store-only ZIP writer + reader. The client exports use ordinary DEFLATE zips, so the
 * reader handles both stored and deflated entries.
 * ------------------------------------------------------------------------------------------- */

const CRC_TABLE = new Uint32Array(256);
for (let n = 0; n < 256; n += 1) {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  CRC_TABLE[n] = c >>> 0;
}

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function writeZip(files) {
  const localChunks = [];
  const centralChunks = [];
  let offset = 0;
  for (const [name, data] of files) {
    const nameBytes = Buffer.from(name, "utf8");
    const checksum = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    localChunks.push(local, nameBytes, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    centralChunks.push(central, nameBytes);
    offset += local.length + nameBytes.length + data.length;
  }

  const centralDirectory = Buffer.concat(centralChunks);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...localChunks, centralDirectory, end]);
}

function readZip(buffer) {
  const signature = Buffer.from([0x50, 0x4b, 0x05, 0x06]);
  const eocd = buffer.lastIndexOf(signature);
  if (eocd < 0) throw new Error("not-a-zip");
  const count = buffer.readUInt16LE(eocd + 10);
  let cursor = buffer.readUInt32LE(eocd + 16);
  const files = new Map();
  for (let index = 0; index < count; index += 1) {
    assert.equal(buffer.readUInt32LE(cursor), 0x02014b50, "bad central directory");
    const method = buffer.readUInt16LE(cursor + 10);
    const checksum = buffer.readUInt32LE(cursor + 16);
    const compressedSize = buffer.readUInt32LE(cursor + 20);
    const uncompressedSize = buffer.readUInt32LE(cursor + 24);
    const nameLength = buffer.readUInt16LE(cursor + 28);
    const extraLength = buffer.readUInt16LE(cursor + 30);
    const commentLength = buffer.readUInt16LE(cursor + 32);
    const localOffset = buffer.readUInt32LE(cursor + 42);
    const name = buffer.toString("utf8", cursor + 46, cursor + 46 + nameLength);
    cursor += 46 + nameLength + extraLength + commentLength;

    assert.equal(buffer.readUInt32LE(localOffset), 0x04034b50, "bad local header");
    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const compressed = buffer.subarray(dataStart, dataStart + compressedSize);
    const data = method === 0 ? Buffer.from(compressed) : inflateRawSync(compressed);
    assert.equal(data.length, uncompressedSize, `size mismatch for ${name}`);
    assert.equal(crc32(data), checksum, `crc mismatch for ${name}`);
    files.set(name, data);
  }
  return files;
}

/* ---------------------------------------------------------------------------------------------
 * Backup format
 * ------------------------------------------------------------------------------------------- */

function makeManifest(entries, extra = {}) {
  return {
    version: 1,
    exported_at: Date.now(),
    count: entries.length,
    entries: entries
      .slice()
      .sort((a, b) => a.created_at - b.created_at || a.id.localeCompare(b.id))
      .map(({ id, created_at }) => ({ id, created_at })),
    ...extra,
  };
}

function writeBackup(outPath, manifest, envelopes) {
  const files = [];
  files.push(["manifest.json", Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, "utf8")]);
  for (const [id, envelope] of envelopes) {
    files.push([`${id}.json`, Buffer.from(envelope, "utf8")]);
  }
  writeFileSync(outPath, writeZip(files));
  return outPath;
}

function readBackup(inputPath) {
  const files = readZip(readFileSync(inputPath));
  const manifestRaw = files.get("manifest.json");
  if (!manifestRaw) throw new Error("missing-manifest");
  const manifest = JSON.parse(manifestRaw.toString("utf8"));
  if (!Array.isArray(manifest.entries)) throw new Error("invalid-manifest");
  const envelopes = new Map();
  for (const entry of manifest.entries) {
    const raw = files.get(`${entry.id}.json`);
    if (!raw) throw new Error(`missing-envelope:${entry.id}`);
    envelopes.set(entry.id, raw.toString("utf8"));
  }
  return { manifest, envelopes, files };
}

/* ---------------------------------------------------------------------------------------------
 * Relay helpers
 * ------------------------------------------------------------------------------------------- */

async function relayRequest(base, secret, userId, pathName, init = {}) {
  const response = await fetch(`${base.replace(/\/+$/, "")}${pathName}`, {
    ...init,
    headers: {
      ...(init.headers ?? {}),
      "X-Relay-Secret": secret,
      "X-User-Id": userId,
    },
  });
  if (!response.ok) {
    throw new Error(`relay-${response.status}`);
  }
  return response;
}

async function listRelay(base, secret, userId, since = 0) {
  const response = await relayRequest(base, secret, userId, `/list?since=${since}`);
  const rows = await response.json();
  if (!Array.isArray(rows)) throw new Error("relay-invalid-list");
  return rows;
}

async function getRelayBlob(base, secret, userId, id) {
  const response = await relayRequest(base, secret, userId, `/blob/${encodeURIComponent(id)}`);
  const text = await response.text();
  parseEnvelope(text);
  return text;
}

async function putRelayBlob(base, secret, userId, id, envelope, createdAt) {
  await relayRequest(base, secret, userId, `/blob/${encodeURIComponent(id)}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      "X-Created-At": String(createdAt),
    },
    body: envelope,
  });
}

/* ---------------------------------------------------------------------------------------------
 * Commands
 * ------------------------------------------------------------------------------------------- */

async function demo() {
  const passphrase = getPassphrase();
  const salt = new Uint8Array(16);
  for (let index = 0; index < salt.length; index += 1) salt[index] = (index * 37 + 11) & 0xff;
  const key = await deriveKeyBytes(passphrase, salt);
  const corpus = JSON.parse(
    readFileSync(path.join(ROOT, "docs", "test-vectors", "search.json"), "utf8"),
  ).corpus;
  const entries = [];
  const envelopes = new Map();
  for (const row of corpus) {
    const payload = { type: row.type };
    for (const field of ["url", "title", "text", "ocr_text"]) {
      if (row[field]) payload[field] = row[field];
    }
    const envelope = await encrypt(key, payload);
    entries.push({ id: row.id, created_at: row.created_at });
    envelopes.set(row.id, envelopeToJson(envelope));
  }
  const out = option("--out", path.join(ROOT, "recall-backup-demo.zip"));
  const manifest = makeManifest(entries, {
    salt_b64: Buffer.from(salt).toString("base64"),
    note: "Demo backup generated from docs/test-vectors/search.json",
  });
  writeBackup(out, manifest, envelopes);
  console.log(`[backup] wrote ${entries.length} encrypted captures to ${out}`);
  console.log("[backup] verify with: pnpm export:backup --input <file> --verify");
}

async function verify() {
  const input = option("--input");
  if (!input) fail("--input <backup.zip> is required");
  const passphrase = getPassphrase();
  const { manifest, envelopes } = readBackup(input);
  let saltB64 = option("--salt-b64") ?? manifest.salt_b64 ?? null;
  if (!saltB64) fail("backup has no salt_b64; pass --salt-b64");
  const salt = Buffer.from(saltB64, "base64");
  if (salt.length !== 16) fail("salt must be 16 bytes");
  const key = await deriveKeyBytes(passphrase, salt);
  let verified = 0;
  for (const entry of manifest.entries) {
    const envelopeText = envelopes.get(entry.id);
    const payload = await decrypt(key, parseEnvelope(envelopeText));
    canonicalPayloadJson(payload);
    verified += 1;
  }
  assert.equal(verified, manifest.count, "manifest count mismatch");
  console.log(`[backup] verified ${verified} envelopes; no plaintext was printed`);
}

async function exportRelay() {
  const relay = option("--relay");
  const userId = option("--user-id");
  if (!relay || !userId) fail("--relay <url> and --user-id <uuid> are required");
  const secret = getRelaySecret();
  const since = Number(option("--since", "0"));
  const rows = await listRelay(relay, secret, userId, Number.isFinite(since) ? since : 0);
  const envelopes = new Map();
  for (const row of rows) {
    envelopes.set(row.id, await getRelayBlob(relay, secret, userId, row.id));
  }
  const out = option("--out", path.join(ROOT, "recall-backup.zip"));
  const manifest = makeManifest(
    rows.map((row) => ({ id: row.id, created_at: row.created_at })),
    { source: "relay", salt_b64: option("--salt-b64") ?? undefined },
  );
  writeBackup(out, manifest, envelopes);
  console.log(`[backup] downloaded ${envelopes.size} envelopes to ${out}`);
}

async function importRelay() {
  const input = option("--input");
  const relay = option("--relay");
  const userId = option("--user-id");
  if (!input || !relay || !userId) fail("--input, --relay and --user-id are required");
  const secret = getRelaySecret();
  const { manifest, envelopes } = readBackup(input);
  if (!flag("--upload")) {
    console.log(`[backup] dry run: ${envelopes.size} envelopes ready; pass --upload to send`);
    return;
  }
  let pushed = 0;
  let failed = 0;
  for (const entry of manifest.entries) {
    try {
      await putRelayBlob(relay, secret, userId, entry.id, envelopes.get(entry.id), entry.created_at);
      pushed += 1;
    } catch {
      failed += 1;
    }
  }
  console.log(`[backup] uploaded ${pushed} envelopes, ${failed} failed`);
  if (failed > 0) process.exitCode = 1;
}

async function main() {
  if (flag("--demo")) return demo();
  if (flag("--verify")) return verify();
  if (flag("--upload")) return importRelay();
  if (option("--relay")) return exportRelay();
  console.error(
    [
      "Usage:",
      "  pnpm export:backup --demo [--out file.zip]",
      "  pnpm export:backup --input file.zip --verify",
      "  pnpm export:backup --relay <url> --user-id <uuid> [--since ms] [--out file.zip]",
      "  pnpm export:backup --input file.zip --relay <url> --user-id <uuid> --upload",
    ].join("\n"),
  );
  process.exitCode = 1;
}

main().catch((error) => fail(error.message));
