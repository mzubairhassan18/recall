import type { CapturePayload, CaptureType, SearchResult, SyncReport } from "@recall/shared-types";
import { base64ToBytes, bytesToBase64, importAesKey } from "@recall/crypto-core";
import { CaptureStore } from "./lib/db.js";
import { EncryptedPersistence } from "./lib/encrypted-persistence.js";
import { IdbPersistence } from "./lib/idb-persistence.js";
import {
  clearSessionKey,
  loadConfig,
  loadSessionKey,
  loadSynonyms,
  saveConfig,
  saveSessionKey,
  type StoredConfig,
} from "./lib/config.js";
import { createIdentity, unlockIdentity } from "./lib/identity.js";
import { RelayClient } from "./lib/relay-client.js";
import { RELAY_SECRET, RELAY_URL } from "./lib/relay-defaults.js";
import { syncNow } from "./lib/sync.js";
import type { PopupRequest, PopupResponse, StatusData } from "./lib/messages.js";

const SYNC_ALARM = "recall-sync";
const MENU_SELECTION = "recall-save-selection";
const MENU_PAGE = "recall-save-page";

let key: CryptoKey | null = null;
let storePromise: Promise<CaptureStore> | null = null;
let configCache: StoredConfig | null = null;
let syncPromise: Promise<SyncReport> | null = null;
let lastSyncAt: number | null = null;
let lastSyncError: string | null = null;

/* ------------------------------------------------------------------ */
/* state                                                               */
/* ------------------------------------------------------------------ */

async function getConfig(): Promise<StoredConfig> {
  if (!configCache) configCache = await loadConfig();
  return configCache;
}

async function getKey(): Promise<CryptoKey | null> {
  if (key) return key;
  const raw = await loadSessionKey();
  if (!raw) return null;
  try {
    key = await importAesKey(raw);
  } finally {
    raw.fill(0);
  }
  return key;
}

async function requireKey(): Promise<CryptoKey> {
  const current = await getKey();
  if (!current) throw new Error("locked");
  return current;
}

async function getStore(): Promise<CaptureStore> {
  const current = await requireKey();
  if (!storePromise) {
    storePromise = (async () => {
      const idb = await IdbPersistence.open();
      const persistence = new EncryptedPersistence(idb, current);
      return CaptureStore.open(persistence);
    })().catch((error: unknown) => {
      storePromise = null;
      throw error;
    });
  }
  return storePromise;
}

function resetStore(): void {
  const pending = storePromise;
  storePromise = null;
  void pending?.then((store) => store.close()).catch(() => undefined);
}

function flashBadge(text: string, color = "#455a64"): void {
  void chrome.action.setBadgeBackgroundColor({ color });
  void chrome.action.setBadgeText({ text });
  setTimeout(() => void chrome.action.setBadgeText({ text: "" }), 2500);
}

/* ------------------------------------------------------------------ */
/* sync                                                                */
/* ------------------------------------------------------------------ */

async function runSync(): Promise<SyncReport> {
  if (syncPromise) return syncPromise;

  syncPromise = (async () => {
    const config = await getConfig();
    if (!config.fingerprint) {
      return { pulled: 0, uploaded: 0, failed: 0, cursor: config.syncCursor, localOnly: true };
    }
    const currentKey = await requireKey();
    const store = await getStore();
    const client =
      config.localOnly || !config.relayUrl || !config.userId
        ? null
        : new RelayClient({
            relayUrl: config.relayUrl,
            relaySecret: config.relaySecret ?? "",
            userId: config.userId,
          });

    const report = await syncNow({ store, key: currentKey, client, config });
    if (report.cursor !== config.syncCursor) {
      config.syncCursor = report.cursor;
      await saveConfig(config);
      configCache = config;
    }
    lastSyncAt = Date.now();
    lastSyncError = null;
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

/* ------------------------------------------------------------------ */
/* capture                                                             */
/* ------------------------------------------------------------------ */

async function saveCapture(payload: CapturePayload): Promise<string> {
  await requireKey();
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
  flashBadge("✓", "#2e7d32");
  scheduleSync();
  return id;
}

async function handleContextMenu(info: chrome.contextMenus.OnClickData, tab?: chrome.tabs.Tab): Promise<void> {
  try {
    if (!(await getKey())) {
      flashBadge("🔒", "#c62828");
      return;
    }
    if (info.menuItemId === MENU_SELECTION && info.selectionText) {
      const payload: CapturePayload = { type: "text", text: info.selectionText };
      if (tab?.url) payload.url = tab.url;
      if (tab?.title) payload.title = tab.title;
      await saveCapture(payload);
      return;
    }
    if (tab?.url) {
      await saveCapture({ type: "url", url: tab.url, title: tab.title ?? "" });
    }
  } catch {
    flashBadge("!", "#c62828");
  }
}

/* ------------------------------------------------------------------ */
/* setup / unlock                                                      */
/* ------------------------------------------------------------------ */

async function setup(passphrase: string): Promise<StatusData> {
  const config = await getConfig();
  if (config.fingerprint) throw new Error("already-configured");
  if (typeof passphrase !== "string" || passphrase.length < 8) throw new Error("passphrase-too-short");

  // Relay credentials are compiled into the extension so setup stays one field.
  const normalizedUrl = RELAY_URL.trim().replace(/\/+$/, "");
  const relaySecret = RELAY_SECRET.trim();
  const register = (username: string) =>
    new RelayClient({ relayUrl: normalizedUrl, relaySecret, userId: "" }).register(username);

  const identity = await createIdentity(passphrase, register);
  const next: StoredConfig = {
    relayUrl: normalizedUrl,
    relaySecret,
    username: identity.username,
    userId: identity.userId,
    salt: identity.saltB64,
    syncCursor: 0,
    localOnly: false,
    fingerprint: identity.fingerprint,
  };

  await saveConfig(next);
  configCache = next;
  await saveSessionKey(identity.keyBytes);
  identity.keyBytes.fill(0);
  key = identity.key;
  resetStore();

  if (!next.localOnly) await runSync().catch(() => undefined);
  return buildStatus();
}

async function unlock(passphrase: string): Promise<StatusData> {
  const config = await getConfig();
  if (!config.fingerprint || !config.salt) throw new Error("not-configured");
  const identity = await unlockIdentity(passphrase, config.salt, config.fingerprint);
  await saveSessionKey(identity.keyBytes);
  identity.keyBytes.fill(0);
  key = identity.key;
  resetStore();
  if (!config.localOnly) await runSync().catch(() => undefined);
  return buildStatus();
}

async function lock(): Promise<StatusData> {
  key = null;
  resetStore();
  await clearSessionKey();
  return buildStatus();
}

/* ------------------------------------------------------------------ */
/* status + search                                                     */
/* ------------------------------------------------------------------ */

async function buildStatus(): Promise<StatusData> {
  const config = await getConfig();
  const currentKey = await getKey();
  let count = 0;
  if (currentKey) {
    try {
      count = (await getStore()).count();
    } catch {
      count = 0;
    }
  }
  return {
    configured: Boolean(config.fingerprint),
    locked: !currentKey,
    localOnly: config.localOnly,
    relayUrl: config.relayUrl,
    count,
    syncing: syncPromise !== null,
    lastSyncAt,
    lastSyncError,
  };
}

async function search(query: string): Promise<SearchResult[]> {
  const store = await getStore();
  return store.search(query, await loadSynonyms());
}

async function timeline(captureType?: CaptureType, domain?: string): Promise<SearchResult[]> {
  const store = await getStore();
  return store.timeline({ type: captureType, domain, limit: 100 });
}

async function saveActivePage(): Promise<StatusData> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.url) throw new Error("no-active-tab");
  await saveCapture({ type: "url", url: tab.url, title: tab.title ?? "" });
  return buildStatus();
}

/* ------------------------------------------------------------------ */
/* message router                                                      */
/* ------------------------------------------------------------------ */

async function handleRequest(request: PopupRequest): Promise<unknown> {
  switch (request.type) {
    case "status":
      return buildStatus();
    case "open":
      if ((await getConfig()).fingerprint && (await getKey())) scheduleSync();
      return buildStatus();
    case "setup":
      return setup(request.passphrase);
    case "unlock":
      return unlock(request.passphrase);
    case "lock":
      return lock();
    case "search":
      return search(request.query);
    case "timeline":
      return timeline(request.captureType, request.domain);
    case "capture": {
      const row = (await getStore()).getCapture(request.id);
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
    case "save-page":
      return saveActivePage();
    case "sync": {
      const report = await runSync();
      return { pulled: report.pulled, uploaded: report.uploaded, failed: report.failed };
    }
    default:
      throw new Error("unknown-request");
  }
}

chrome.runtime.onMessage.addListener(
  (request: PopupRequest, _sender, sendResponse: (response: PopupResponse) => void) => {
    handleRequest(request)
      .then((data) => sendResponse({ ok: true, data: data as never }))
      .catch((error: unknown) => sendResponse({ ok: false, error: safeError(error) }));
    return true;
  },
);

function safeError(error: unknown): string {
  if (error instanceof Error && /^[a-z0-9-]{3,64}$/.test(error.message)) return error.message;
  return "request-failed";
}

/* ------------------------------------------------------------------ */
/* lifecycle                                                           */
/* ------------------------------------------------------------------ */

function installMenus(): void {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_SELECTION,
      title: "Save to Recall",
      contexts: ["selection"],
    });
    chrome.contextMenus.create({
      id: MENU_PAGE,
      title: "Save page to Recall",
      contexts: ["page"],
    });
  });
}

function ensureAlarm(): void {
  chrome.alarms.get(SYNC_ALARM, (alarm) => {
    if (!alarm) chrome.alarms.create(SYNC_ALARM, { periodInMinutes: 0.5 });
  });
}

chrome.runtime.onInstalled.addListener(() => {
  installMenus();
  ensureAlarm();
});

chrome.runtime.onStartup.addListener(() => {
  ensureAlarm();
  void getConfig().then((config) => {
    if (!config.fingerprint) void chrome.action.setBadgeText({ text: "!" });
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  void handleContextMenu(info, tab);
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== SYNC_ALARM) return;
  void getConfig()
    .then(async (config) => {
      if (!config.fingerprint || !(await getKey())) return;
      await runSync();
    })
    .catch(() => undefined);
});

// Warm the config + session key when the worker starts.
void getConfig().catch(() => undefined);
void getKey().catch(() => undefined);
