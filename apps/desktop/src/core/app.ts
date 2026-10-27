import { join } from "node:path";
import type { CapturePayload, CaptureType, SearchResult, SyncReport } from "@recall/shared-types";
import { base64ToBytes, bytesToBase64 } from "@recall/crypto-core";
import type { SynonymIndex } from "@recall/search-core";
import { CaptureStore, MemoryPersistence } from "../lib/db.js";
import { EncryptedPersistence } from "../lib/encrypted-persistence.js";
import { FilePersistence } from "../lib/file-persistence.js";
import { loadConfig, saveConfig, type SecretBox, type StoredConfig } from "../lib/config.js";
import { createIdentity, unlockIdentity } from "../lib/identity.js";
import { RelayClient } from "../lib/relay-client.js";
import { RELAY_SECRET, RELAY_URL } from "../lib/relay-defaults.js";
import { syncNow } from "../lib/sync.js";
import { loadSynonyms } from "../lib/synonyms.js";
import * as platform from "../lib/platform.js";
import type {
  CaptureDetail,
  CaptureResult,
  IpcResult,
  SearchInput,
  SetupInput,
  StatusData,
  SyncResult,
  TimelineInput,
} from "../lib/messages.js";

/**
 * Desktop app core. This is the previous Electron main process, unchanged in behaviour:
 * the same crypto, SQLite store, sync loop, capture rules, and error codes. Only the shell
 * calls moved to Rust (`../lib/platform.ts`); everything below is the original logic.
 */

const SYNC_INTERVAL_MS = 30_000;

const synonymsFile = "synonyms.json";

let configFile = "";
let databaseFile = "";
let secretBox: SecretBox;
let key: CryptoKey | null = null;
let storePromise: Promise<CaptureStore> | null = null;
let configCache: StoredConfig | null = null;
let synonyms: SynonymIndex | null = null;
let syncPromise: Promise<SyncReport> | null = null;
let syncTimer: ReturnType<typeof setInterval> | null = null;
let lastSyncAt: number | null = null;
let lastSyncError: string | null = null;

const changedListeners = new Set<() => void>();
const focusSearchListeners = new Set<() => void>();

/* ------------------------------------------------------------------ */
/* events (was ipcRenderer.send from the main process)                 */
/* ------------------------------------------------------------------ */

export function onChanged(callback: () => void): () => void {
  changedListeners.add(callback);
  return () => changedListeners.delete(callback);
}

export function onFocusSearch(callback: () => void): () => void {
  focusSearchListeners.add(callback);
  return () => focusSearchListeners.delete(callback);
}

function emitChanged(): void {
  for (const listener of changedListeners) listener();
}

/* ------------------------------------------------------------------ */
/* state                                                               */
/* ------------------------------------------------------------------ */

function requireKey(): CryptoKey {
  if (!key) throw new Error("locked");
  return key;
}

async function getConfig(): Promise<StoredConfig> {
  configCache ??= await loadConfig(configFile, secretBox);
  return configCache;
}

async function persistConfig(next: StoredConfig): Promise<void> {
  configCache = next;
  await saveConfig(configFile, next, secretBox);
}

async function getStore(): Promise<CaptureStore> {
  const current = requireKey();
  storePromise ??= (async () => {
    const bytes = new FilePersistence(databaseFile);
    return CaptureStore.open(new EncryptedPersistence(bytes, current));
  })().catch((error: unknown) => {
    storePromise = null;
    throw error;
  });
  return storePromise;
}

function resetStore(): void {
  const pending = storePromise;
  storePromise = null;
  void pending?.then((store) => store.close()).catch(() => undefined);
}

function broadcastChanged(): void {
  emitChanged();
  void getConfig()
    .then(async (config) => {
      if (!key) {
        void platform.setTrayTooltip("Recall — locked");
        return;
      }
      try {
        const store = await getStore();
        void platform.setTrayTooltip(
          `Recall — ${store.count()} captures${config.localOnly ? "" : " · synced"}`,
        );
      } catch {
        void platform.setTrayTooltip("Recall");
      }
    })
    .catch(() => undefined);
}

/* ------------------------------------------------------------------ */
/* sync                                                                */
/* ------------------------------------------------------------------ */

async function runSync(): Promise<SyncReport> {
  if (syncPromise) return syncPromise;

  syncPromise = (async () => {
    const config = await getConfig();
    if (!config.fingerprint || !key) {
      return { pulled: 0, uploaded: 0, failed: 0, cursor: config.syncCursor, localOnly: true };
    }
    const store = await getStore();
    const client =
      config.localOnly || !config.relayUrl || !config.userId
        ? null
        : new RelayClient({
            relayUrl: config.relayUrl,
            relaySecret: config.relaySecret ?? "",
            userId: config.userId,
          });

    const report = await syncNow({ store, key, client, config });
    if (report.cursor !== config.syncCursor) {
      config.syncCursor = report.cursor;
      await persistConfig(config);
    }
    lastSyncAt = Date.now();
    lastSyncError = null;
    broadcastChanged();
    return report;
  })()
    .catch((error: unknown) => {
      lastSyncError = safeError(error);
      throw error;
    })
    .finally(() => {
      syncPromise = null;
    });

  return syncPromise;
}

function scheduleSync(): void {
  void runSync().catch(() => undefined);
}

function startSyncTimer(): void {
  if (syncTimer) return;
  syncTimer = setInterval(() => {
    if (!key) return;
    void getConfig()
      .then((config) => {
        if (!config.localOnly) scheduleSync();
      })
      .catch(() => undefined);
  }, SYNC_INTERVAL_MS);
}

/* ------------------------------------------------------------------ */
/* capture                                                             */
/* ------------------------------------------------------------------ */

async function saveCapture(payload: CapturePayload): Promise<string> {
  requireKey();
  const store = await getStore();
  const id = crypto.randomUUID();
  await store.insertCapture({
    id,
    created_at: Date.now(),
    type: payload.type,
    url: payload.url ?? null,
    title: payload.title ?? null,
    text: payload.text ?? null,
    ocr_text: payload.ocr_text ?? null,
    thumbnail: payload.thumbnail_b64 ? base64ToBytes(payload.thumbnail_b64) : null,
    synced: 0,
  });
  broadcastChanged();
  scheduleSync();
  return id;
}

/** Read the Windows CF_HTML payload, which can carry the source page URL. */
async function readClipboardHtml(): Promise<string> {
  return platform.clipboardHtml();
}

function sourceUrlFromHtml(html: string): string | null {
  const match = html.match(/SourceURL:\s*([^\r\n]+)/i);
  if (!match) return null;
  const value = match[1]?.trim();
  return value && /^https?:\/\//i.test(value) ? value : null;
}

function firstUrlIn(text: string): string | null {
  const match = text.match(/https?:\/\/[^\s<>"']+/i);
  return match ? match[0].replace(/[.,;!?)\]]+$/, "") : null;
}

function hostOf(value: string): string | null {
  try {
    return new URL(value).hostname.replace(/^www\./i, "") || null;
  } catch {
    return null;
  }
}

async function captureClipboard(): Promise<CaptureResult> {
  if (!key) {
    await platform.notify("Recall is locked", "Open Recall and unlock to capture.");
    return { saved: false, reason: "locked" };
  }

  const text = (await platform.clipboardText()).trim();
  if (text.length > 0) {
    const isUrl = /^https?:\/\/\S+$/i.test(text);
    const sourceUrl = sourceUrlFromHtml(await readClipboardHtml()) ?? (isUrl ? text : firstUrlIn(text));
    const sourceHost = sourceUrl ? hostOf(sourceUrl) : null;
    const app = await platform.clipboardOwnerApp();
    const title = isUrl
      ? [app, sourceHost ?? hostOf(text)].filter(Boolean).join(" — ") || text
      : sourceUrl
        ? [app ? `Copied from ${app}` : "Copied link", sourceHost].filter(Boolean).join(" · ")
        : app
          ? `Copied from ${app}`
          : undefined;
    const payload: CapturePayload = isUrl
      ? { type: "url", url: text, ...(title ? { title } : {}) }
      : {
          type: "text",
          text,
          ...(sourceUrl ? { url: sourceUrl } : {}),
          ...(title ? { title } : {}),
        };
    const id = await saveCapture(payload);
    await platform.notify("Saved to Recall", preview(payload));
    return { saved: true, kind: payload.type, id };
  }

  const image = await platform.clipboardImage();
  if (image) {
    const app = await platform.clipboardOwnerApp();
    const payload: CapturePayload = {
      type: "screenshot",
      title: app ? `Image from ${app}` : "Clipboard image",
      thumbnail_b64: image.png_b64,
    };
    const id = await saveCapture(payload);
    await platform.notify("Screenshot saved to Recall", "Stored locally as an encrypted thumbnail.");
    return { saved: true, kind: "screenshot", id };
  }

  await platform.notify("Nothing to capture", "The clipboard has no text or image.");
  return { saved: false, reason: "empty-clipboard" };
}

function preview(payload: CapturePayload): string {
  const value = payload.title || payload.text || payload.url || "";
  const flattened = value.replace(/\s+/g, " ").trim();
  return flattened.length > 120 ? `${flattened.slice(0, 117)}…` : flattened || "Captured.";
}

/* ------------------------------------------------------------------ */
/* setup / unlock                                                      */
/* ------------------------------------------------------------------ */

async function setup(input: SetupInput): Promise<StatusData> {
  const config = await getConfig();
  if (config.fingerprint) throw new Error("already-configured");
  const passphrase = typeof input?.passphrase === "string" ? input.passphrase : "";
  if (passphrase.length < 8) throw new Error("passphrase-too-short");

  // Setup is deliberately one field: relay credentials are compiled into the client.
  const relayUrl = (input.relayUrl ?? RELAY_URL).trim().replace(/\/+$/, "");
  const relaySecret = (input.relaySecret ?? RELAY_SECRET).trim();
  if (relayUrl && !/^https?:\/\//i.test(relayUrl)) throw new Error("invalid-relay-url");
  if (relayUrl && relaySecret.length === 0) throw new Error("relay-secret-required");

  const register = relayUrl
    ? (username: string) =>
        new RelayClient({ relayUrl, relaySecret, userId: "" }).register(username)
    : null;

  const identity = await createIdentity(passphrase, register);
  await persistConfig({
    relayUrl: relayUrl || null,
    relaySecret: relayUrl ? relaySecret : null,
    username: identity.username,
    userId: identity.userId,
    salt: identity.saltB64,
    syncCursor: 0,
    localOnly: !relayUrl,
    fingerprint: identity.fingerprint,
  });

  identity.keyBytes.fill(0);
  key = identity.key;
  resetStore();
  startSyncTimer();
  if (relayUrl) await runSync().catch(() => undefined);
  return buildStatus();
}

async function unlock(passphrase: string): Promise<StatusData> {
  const config = await getConfig();
  if (!config.fingerprint || !config.salt) throw new Error("not-configured");
  const identity = await unlockIdentity(passphrase, config.salt, config.fingerprint);
  identity.keyBytes.fill(0);
  key = identity.key;
  resetStore();
  startSyncTimer();
  if (!config.localOnly) await runSync().catch(() => undefined);
  broadcastChanged();
  return buildStatus();
}

async function lock(): Promise<StatusData> {
  key = null;
  resetStore();
  lastSyncError = null;
  broadcastChanged();
  return buildStatus();
}

/* ------------------------------------------------------------------ */
/* status + search                                                     */
/* ------------------------------------------------------------------ */

async function buildStatus(): Promise<StatusData> {
  const config = await getConfig();
  let count = 0;
  if (key) {
    try {
      count = (await getStore()).count();
    } catch {
      count = 0;
    }
  }
  return {
    configured: Boolean(config.fingerprint),
    locked: !key,
    localOnly: config.localOnly,
    relayUrl: config.relayUrl,
    count,
    syncing: syncPromise !== null,
    lastSyncAt,
    lastSyncError,
  };
}

async function getSynonyms(): Promise<SynonymIndex> {
  synonyms ??= await loadSynonyms(synonymsFile);
  return synonyms;
}

async function search(input: SearchInput): Promise<SearchResult[]> {
  const store = await getStore();
  return store.search(input?.query ?? "", await getSynonyms());
}

async function timeline(input: TimelineInput): Promise<SearchResult[]> {
  const store = await getStore();
  return store.timeline({
    type: input?.captureType,
    domain: input?.domain,
    limit: 200,
  });
}

async function detail(id: string): Promise<CaptureDetail> {
  const store = await getStore();
  const row = store.getCapture(id);
  if (!row) throw new Error("not-found");
  return {
    id: row.id,
    title: row.title ?? "",
    text: row.text ?? "",
    ocr_text: row.ocr_text ?? "",
    url: row.url,
    type: row.type,
    created_at: row.created_at,
    ...(row.thumbnail ? { thumbnail_b64: bytesToBase64(row.thumbnail) } : {}),
  };
}

/* ------------------------------------------------------------------ */
/* IPC (unchanged channel names and result envelope)                   */
/* ------------------------------------------------------------------ */

type Handler = (argument: unknown) => Promise<unknown>;
const handlers = new Map<string, Handler>();

function handle<T, A = unknown>(channel: string, fn: (argument: A) => Promise<T>): void {
  handlers.set(channel, async (argument: unknown) => fn(argument as A));
}

function registerIpc(): void {
  handle("recall:status", () => buildStatus());
  handle("recall:setup", (input: SetupInput) => setup(input));
  handle("recall:unlock", (passphrase: string) => unlock(passphrase));
  handle("recall:lock", () => lock());
  handle("recall:search", (input: SearchInput) => search(input));
  handle("recall:timeline", (input: TimelineInput) => timeline(input));
  handle("recall:detail", (id: string) => detail(id));
  handle("recall:save-clipboard", () => captureClipboard());
  handle("recall:sync", async (): Promise<SyncResult> => {
    const report = await runSync();
    return { pulled: report.pulled, uploaded: report.uploaded, failed: report.failed };
  });
  handle("recall:open-external", async (url: string) => {
    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch {
      throw new Error("invalid-url");
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") throw new Error("invalid-url");
    await platform.openExternal(parsed.toString());
  });
  handle("recall:quit", async () => {
    await platform.quit();
  });
}

export async function dispatch(channel: string, argument?: unknown): Promise<IpcResult<unknown>> {
  try {
    await boot();
    const handler = handlers.get(channel);
    if (!handler) throw new Error("request-failed");
    return { ok: true, data: await handler(argument) };
  } catch (error) {
    return { ok: false, error: safeError(error) };
  }
}

function safeError(error: unknown): string {
  if (error instanceof Error && /^[a-z0-9-]{3,64}$/.test(error.message)) return error.message;
  return "request-failed";
}

/* ------------------------------------------------------------------ */
/* smoke (CI / headless verification)                                  */
/* ------------------------------------------------------------------ */

async function runSmoke(): Promise<void> {
  try {
    const store = await CaptureStore.open(new MemoryPersistence());
    await store.insertCapture({
      id: "smoke",
      created_at: Date.now(),
      type: "text",
      text: "distributed locks and fencing tokens",
      synced: 0,
    });
    const hits = await store.search("distributed locks", { lookup: new Map(), size: 0 });
    const count = store.count();
    store.close();
    if (count !== 1) throw new Error("store-count-failed");
    if (hits.length === 0) throw new Error("store-search-failed");

    // The synonym dictionary ships as a frontend asset; this proves the bundle path works.
    const dictionary = await getSynonyms();
    if (dictionary.size === 0) throw new Error("synonyms-missing");

    const bridge = typeof (globalThis as { recall?: unknown }).recall === "object";
    if (bridge !== true) throw new Error("bridge-missing");
    const rendererReady = document.documentElement.dataset.ready === "1";
    if (rendererReady !== true) throw new Error("renderer-script-missing");
    await platform.smokeReport(
      true,
      `ok store=${count} results=${hits.length} bridge=${String(bridge)} renderer=${String(rendererReady)}`,
    );
  } catch (error) {
    await platform.smokeReport(false, `failed ${error instanceof Error ? error.message : "unknown"}`);
  }
}

/* ------------------------------------------------------------------ */
/* lifecycle                                                           */
/* ------------------------------------------------------------------ */

let booted: Promise<void> | null = null;

export function boot(): Promise<void> {
  booted ??= bootstrap().catch((error: unknown) => {
    booted = null;
    throw error;
  });
  return booted;
}

async function bootstrap(): Promise<void> {
  const paths = await platform.appPaths();
  configFile = join(paths.userData, "config.json");
  databaseFile = join(paths.userData, "recall.db.enc");
  secretBox = await platform.secretBox();
  configCache = await loadConfig(configFile, secretBox);
  registerIpc();

  await platform.onCapture(() => {
    void captureClipboard().catch(() => undefined);
  });
  await platform.onTraySync(() => {
    void runSync()
      .then(() => broadcastChanged())
      .catch(() => undefined);
  });
  await platform.onTrayLock(() => {
    void lock().catch(() => undefined);
  });
  await platform.onFocusSearch(() => {
    for (const listener of focusSearchListeners) listener();
  });

  if (paths.smoke) {
    await runSmoke();
    return;
  }

  startSyncTimer();
  await platform.frontendReady();
}
