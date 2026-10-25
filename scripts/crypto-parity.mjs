#!/usr/bin/env node
/**
 * Cross-language crypto parity: TypeScript (@recall/crypto-core) vs Kotlin (:core + :parity CLI).
 *
 * Checks the published PBKDF2/GCM vectors, the envelope round-trip fixtures, sync-identity
 * derivation, fingerprints, and randomized envelopes. It never prints keys, passphrases, or
 * plaintext; only counts and fixture names.
 */
import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import assert from "node:assert/strict";
import {
  aesGcmOpenRaw,
  aesGcmSealRaw,
  bytesToHex,
  canonicalPayloadJson,
  decrypt,
  deriveKeyBytes,
  deriveSyncUsername,
  encrypt,
  envelopeToJson,
  hexToBytes,
  keyFingerprint,
  parseEnvelope,
} from "@recall/crypto-core";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const ANDROID = path.join(ROOT, "apps", "android");
const IS_WIN = process.platform === "win32";
const PARITY_BIN = path.join(
  ANDROID,
  "parity",
  "build",
  "install",
  "parity",
  "bin",
  IS_WIN ? "parity.bat" : "parity",
);

function fixture(name) {
  return JSON.parse(readFileSync(path.join(ROOT, "docs", "test-vectors", name), "utf8"));
}

function ensureParityBuilt() {
  if (existsSync(PARITY_BIN)) return;
  console.log("[parity] building Kotlin parity CLI…");
  const env = { ...process.env, GRADLE_USER_HOME: process.env.GRADLE_USER_HOME || "D:\\gcache" };
  const result = IS_WIN
    ? spawnSync("cmd.exe", ["/c", "gradlew.bat", ":parity:installDist"], { cwd: ANDROID, env, stdio: "inherit" })
    : spawnSync("./gradlew", [":parity:installDist"], { cwd: ANDROID, env, stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error("could not build :parity:installDist");
  }
}

function startParity() {
  ensureParityBuilt();
  const child = IS_WIN
    ? spawn("cmd.exe", ["/c", PARITY_BIN], { cwd: ROOT, stdio: ["pipe", "pipe", "inherit"] })
    : spawn(PARITY_BIN, [], { cwd: ROOT, stdio: ["pipe", "pipe", "inherit"] });

  let buffer = "";
  const waiters = [];
  let closed = false;

  child.stdout.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    let newline;
    while ((newline = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      const waiter = waiters.shift();
      if (!waiter) continue;
      try {
        const response = JSON.parse(line);
        if (response.error) waiter.reject(new Error(response.error));
        else waiter.resolve(response);
      } catch (error) {
        waiter.reject(error);
      }
    }
  });

  child.on("exit", (code) => {
    closed = true;
    while (waiters.length > 0) {
      waiters.shift().reject(new Error(`parity CLI exited with code ${code}`));
    }
  });

  return {
    call(request) {
      if (closed) return Promise.reject(new Error("parity CLI is closed"));
      return new Promise((resolve, reject) => {
        waiters.push({ resolve, reject });
        child.stdin.write(`${JSON.stringify(request)}\n`);
      });
    },
    async close() {
      child.stdin.end();
      await new Promise((resolve) => child.once("exit", resolve));
    },
  };
}

async function main() {
  const cli = startParity();
  let checks = 0;
  const ok = (label, count = 1) => {
    checks += count;
    console.log(`[parity] ${label}: ${count} ok`);
  };

  try {
    const version = await cli.call({ op: "version" });
    assert.equal(version.version, 1);

    const pbkdf2 = fixture("pbkdf2.json");
    for (const vector of pbkdf2.vectors) {
      const salt = Buffer.from(vector.salt_utf8, "utf8");
      const expected = bytesToHex(
        await deriveKeyBytes(vector.passphrase, salt, vector.iterations, vector.dklen),
      );
      const actual = await cli.call({
        op: "pbkdf2",
        passphrase: vector.passphrase,
        salt_hex: bytesToHex(salt),
        iterations: vector.iterations,
        dklen: vector.dklen,
      });
      assert.equal(actual.dk_hex, expected, vector.name);
      assert.equal(actual.dk_hex, vector.dk_hex, vector.name);
    }
    ok("PBKDF2 fixtures", pbkdf2.vectors.length);

    const unicodePass = "pässwörd-😀-recall";
    const unicodeSalt = randomBytes(16);
    const unicodeExpected = bytesToHex(await deriveKeyBytes(unicodePass, unicodeSalt, 1000, 32));
    const unicodeActual = await cli.call({
      op: "pbkdf2",
      passphrase: unicodePass,
      salt_hex: bytesToHex(unicodeSalt),
      iterations: 1000,
      dklen: 32,
    });
    assert.equal(unicodeActual.dk_hex, unicodeExpected, "unicode passphrase UTF-8 parity");
    ok("PBKDF2 unicode passphrase");

    for (const passphrase of ["correct horse battery staple", "recall-test-vector", "hunter2"]) {
      const expected = await deriveSyncUsername(passphrase);
      const actual = await cli.call({ op: "sync_username", passphrase });
      assert.equal(actual.username, expected);
    }
    ok("sync username", 3);

    const key = randomBytes(32);
    const expectedFingerprint = await keyFingerprint(key);
    const actualFingerprint = await cli.call({ op: "fingerprint", key_hex: bytesToHex(key) });
    assert.equal(actualFingerprint.fingerprint, expectedFingerprint);
    ok("key fingerprint");

    const gcm = fixture("aesgcm.json");
    for (const vector of gcm.vectors) {
      const rawKey = hexToBytes(vector.key_hex);
      const iv = hexToBytes(vector.iv_hex);
      const plaintext = hexToBytes(vector.plaintext_hex);
      const aad = hexToBytes(vector.aad_hex);
      const sealed = await aesGcmSealRaw(rawKey, iv, plaintext, aad);
      const actual = await cli.call({
        op: "gcm_seal",
        key_hex: vector.key_hex,
        iv_hex: vector.iv_hex,
        plaintext_hex: vector.plaintext_hex,
        aad_hex: vector.aad_hex,
      });
      assert.equal(actual.ciphertext_hex, bytesToHex(sealed.ciphertext), vector.name);
      assert.equal(actual.tag_hex, bytesToHex(sealed.tag), vector.name);
      assert.equal(actual.ciphertext_hex, vector.ciphertext_hex, vector.name);
      assert.equal(actual.tag_hex, vector.tag_hex, vector.name);

      const opened = await cli.call({
        op: "gcm_open",
        key_hex: vector.key_hex,
        iv_hex: vector.iv_hex,
        ct_hex: vector.ciphertext_hex + vector.tag_hex,
        aad_hex: vector.aad_hex,
      });
      assert.equal(opened.plaintext_hex, vector.plaintext_hex, vector.name);
      const tsOpened = await aesGcmOpenRaw(
        rawKey,
        iv,
        hexToBytes(vector.ciphertext_hex),
        hexToBytes(vector.tag_hex),
        aad,
      );
      assert.equal(bytesToHex(tsOpened), vector.plaintext_hex, vector.name);
    }
    ok("AES-256-GCM fixtures", gcm.vectors.length);

    const roundTrip = fixture("envelope-roundtrip.json");
    const roundTripKey = hexToBytes(roundTrip.key_hex);
    for (const vector of roundTrip.vectors) {
      const iv = hexToBytes(vector.iv_hex);
      const tsEnvelope = await encrypt(roundTripKey, vector.payload, { iv });
      const kotlin = await cli.call({
        op: "envelope_seal",
        key_hex: roundTrip.key_hex,
        iv_hex: vector.iv_hex,
        payload: vector.payload,
      });
      assert.equal(kotlin.envelope, envelopeToJson(tsEnvelope), vector.name);

      const kotlinOpened = await cli.call({
        op: "envelope_open",
        key_hex: roundTrip.key_hex,
        envelope: kotlin.envelope,
      });
      const tsPayload = await decrypt(roundTripKey, parseEnvelope(kotlin.envelope));
      assert.equal(kotlinOpened.payload, canonicalPayloadJson(tsPayload), vector.name);
      assert.equal(kotlinOpened.payload, canonicalPayloadJson(vector.payload), vector.name);

      const tsOpened = await decrypt(roundTripKey, parseEnvelope(kotlin.envelope));
      assert.equal(canonicalPayloadJson(tsOpened), canonicalPayloadJson(vector.payload), vector.name);
    }
    ok("envelope round-trip fixtures", roundTrip.vectors.length);

    const payloads = [
      { type: "text", title: "Unicode ✓", text: "Line one\nLine two\twith \"quotes\" and \\ slash 😀" },
      { type: "url", url: "https://example.com/a?b=c&d=e", title: "Example" },
      { type: "screenshot", title: "Screenshot", ocr_text: "Invoice total: $42.00" },
      { type: "text", text: "" },
      { type: "text", text: "control\u0001chars\u001f" },
      { type: "url", url: "https://example.com/", title: "a".repeat(500) },
      { type: "text", text: "日本語のテキストと emoji 🚀" },
      { type: "screenshot", title: "S", ocr_text: "x".repeat(2000), thumbnail_b64: "aGVsbG8=" },
      { type: "text", title: "Quote \"", text: "back\\slash" },
      { type: "text", text: "  spaced  " },
    ];
    for (const [index, payload] of payloads.entries()) {
      const rawKey = randomBytes(32);
      const iv = randomBytes(12);
      const tsEnvelope = await encrypt(rawKey, payload, { iv });
      const kotlin = await cli.call({
        op: "envelope_seal",
        key_hex: bytesToHex(rawKey),
        iv_hex: bytesToHex(iv),
        payload,
      });
      assert.equal(kotlin.envelope, envelopeToJson(tsEnvelope), `random payload ${index}`);
      const opened = await cli.call({
        op: "envelope_open",
        key_hex: bytesToHex(rawKey),
        envelope: kotlin.envelope,
      });
      assert.equal(opened.payload, canonicalPayloadJson(payload), `random payload ${index}`);
    }
    ok("randomized envelopes", payloads.length);

    console.log(`\nPASS: TypeScript and Kotlin crypto agree on ${checks} checks.`);
  } finally {
    await cli.close();
  }
}

main().catch((error) => {
  console.error(`\nFAIL: ${error.message}`);
  process.exitCode = 1;
});
