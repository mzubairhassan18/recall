import type { CapturePayload, SyncReport } from "@recall/shared-types";
import { base64ToBytes, bytesToBase64, importAesKey, keyFingerprint } from "@recall/crypto-core";
import { CaptureStore, type CaptureSummary } from "./lib/db.js";
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
import {
  createPinWrap,
  isValidPin,
  openPinWrap,
  pinLockoutMs,
  type PinWrap,
} from "./lib/pin.js";
import { RelayClient } from "./lib/relay-client.js";
import { RELAY_SECRET, RELAY_URL } from "./lib/relay-defaults.js";
import { syncNow } from "./lib/sync.js";
import type { CaptureQuery, PopupRequest, PopupResponse, StatusData } from "./lib/messages.js";

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

interface CaptureMetadata {
  favorite?: boolean;
  archived?: boolean;
  tags?: string[];
}

async function saveCapture(payload: CapturePayload, meta: CaptureMetadata = {}): Promise<string> {
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
    favorite: meta.favorite ? 1 : 0,
    archived: meta.archived ? 1 : 0,
    tags: meta.tags ?? [],
  });
  flashBadge("✓", "#2e7d32");
  scheduleSync();
  return id;
}

async function saveTypedCapture(
  text: string,
  tags: string[] = [],
  favorite = false,
): Promise<string> {
  const body = typeof text === "string" ? text.trim() : "";
  if (!body) throw new Error("empty-capture");
  return saveCapture({ type: "text", text: body }, { tags, favorite });
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

function pinWrapFromConfig(config: StoredConfig): PinWrap | null {
  if (!config.pinSalt || !config.pinWrapIv || !config.pinWrapCt) return null;
  return { salt: config.pinSalt, iv: config.pinWrapIv, ct: config.pinWrapCt };
}

function activePinLockout(config: StoredConfig): number | null {
  return config.pinLockedUntil > Date.now() ? config.pinLockedUntil : null;
}

async function persistConfig(config: StoredConfig): Promise<void> {
  configCache = config;
  await saveConfig(config);
}

async function clearPinFailures(config: StoredConfig): Promise<void> {
  if (config.pinFailures === 0 && config.pinLockedUntil === 0) return;
  config.pinFailures = 0;
  config.pinLockedUntil = 0;
  await persistConfig(config);
}

async function setup(passphrase: string, pin: string): Promise<StatusData> {
  const config = await getConfig();
  if (config.fingerprint) throw new Error("already-configured");
  if (typeof passphrase !== "string" || passphrase.length < 8) throw new Error("passphrase-too-short");
  if (!isValidPin(pin)) throw new Error("invalid-pin");

  // Relay credentials are compiled into the extension, so setup needs no relay URL or secret.
  const normalizedUrl = RELAY_URL.trim().replace(/\/+$/, "");
  const relaySecret = RELAY_SECRET.trim();
  const register = (username: string) =>
    new RelayClient({ relayUrl: normalizedUrl, relaySecret, userId: "" }).register(username);

  const identity = await createIdentity(passphrase, register);
  let pinWrap: PinWrap;
  try {
    pinWrap = await createPinWrap(pin, identity.keyBytes);
  } catch (error) {
    identity.keyBytes.fill(0);
    throw error;
  }

  const next: StoredConfig = {
    relayUrl: normalizedUrl,
    relaySecret,
    username: identity.username,
    userId: identity.userId,
    salt: identity.saltB64,
    syncCursor: 0,
    localOnly: false,
    fingerprint: identity.fingerprint,
    pinSalt: pinWrap.salt,
    pinWrapIv: pinWrap.iv,
    pinWrapCt: pinWrap.ct,
    pinFailures: 0,
    pinLockedUntil: 0,
  };

  await persistConfig(next);
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
  await clearPinFailures(config);
  if (!config.localOnly) await runSync().catch(() => undefined);
  return buildStatus();
}

async function unlockWithPin(pin: string): Promise<StatusData> {
  const config = await getConfig();
  if (!config.fingerprint || !config.salt) throw new Error("not-configured");
  const wrap = pinWrapFromConfig(config);
  if (!wrap) throw new Error("pin-not-configured");
  if (!isValidPin(pin)) throw new Error("invalid-pin");
  if (config.pinLockedUntil > Date.now()) throw new Error("pin-locked");

  let keyBytes: Uint8Array | null = null;
  try {
    keyBytes = await openPinWrap(pin, wrap);
    if ((await keyFingerprint(keyBytes)) !== config.fingerprint) {
      throw new Error("wrong-pin");
    }
  } catch (error) {
    keyBytes?.fill(0);
    if (error instanceof Error && error.message === "invalid-pin-wrap") throw error;
    config.pinFailures += 1;
    const lockout = pinLockoutMs(config.pinFailures);
    if (lockout > 0) config.pinLockedUntil = Date.now() + lockout;
    await persistConfig(config);
    if (config.pinLockedUntil > Date.now()) throw new Error("pin-locked");
    throw new Error("wrong-pin");
  }

  if (!keyBytes) throw new Error("wrong-pin");
  try {
    await saveSessionKey(keyBytes);
    key = await importAesKey(keyBytes);
  } finally {
    keyBytes.fill(0);
  }
  config.pinFailures = 0;
  config.pinLockedUntil = 0;
  await persistConfig(config);
  resetStore();
  if (!config.localOnly) await runSync().catch(() => undefined);
  return buildStatus();
}

async function setPin(pin: string): Promise<StatusData> {
  const config = await getConfig();
  if (!config.fingerprint || !config.salt) throw new Error("not-configured");
  if (!(await getKey())) throw new Error("locked");
  if (!isValidPin(pin)) throw new Error("invalid-pin");

  const keyBytes = await loadSessionKey();
  if (!keyBytes) throw new Error("locked");
  try {
    const wrap = await createPinWrap(pin, keyBytes);
    config.pinSalt = wrap.salt;
    config.pinWrapIv = wrap.iv;
    config.pinWrapCt = wrap.ct;
    config.pinFailures = 0;
    config.pinLockedUntil = 0;
    await persistConfig(config);
  } finally {
    keyBytes.fill(0);
  }
  return buildStatus();
}

async function removePin(): Promise<StatusData> {
  const config = await getConfig();
  if (!config.fingerprint || !config.salt) throw new Error("not-configured");
  if (!(await getKey())) throw new Error("locked");
  config.pinSalt = null;
  config.pinWrapIv = null;
  config.pinWrapCt = null;
  config.pinFailures = 0;
  config.pinLockedUntil = 0;
  await persistConfig(config);
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
  let archivedCount = 0;
  if (currentKey) {
    try {
      const store = await getStore();
      count = store.count();
      archivedCount = store.countArchived();
    } catch {
      count = 0;
      archivedCount = 0;
    }
  }
  return {
    configured: Boolean(config.fingerprint),
    locked: !currentKey,
    pinConfigured: pinWrapFromConfig(config) !== null,
    pinLockedUntil: activePinLockout(config),
    localOnly: config.localOnly,
    relayUrl: config.relayUrl,
    count,
    archivedCount,
    syncing: syncPromise !== null,
    lastSyncAt,
    lastSyncError,
  };
}

function filterFromQuery(query: CaptureQuery) {
  return {
    type: query.captureType,
    domain: query.domain,
    favoritesOnly: query.favoritesOnly,
    tag: query.tag,
    descending: query.descending,
  };
}

async function search(query: string, filters: CaptureQuery): Promise<CaptureSummary[]> {
  const store = await getStore();
  return store.search(query, await loadSynonyms(), Date.now(), filterFromQuery(filters));
}

async function timeline(filters: CaptureQuery): Promise<CaptureSummary[]> {
  const store = await getStore();
  return store.timeline({ ...filterFromQuery(filters), limit: 200 });
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
      return setup(request.passphrase, request.pin);
    case "unlock":
      return unlock(request.passphrase);
    case "unlock-pin":
      return unlockWithPin(request.pin);
    case "set-pin":
      return setPin(request.pin);
    case "remove-pin":
      return removePin();
    case "lock":
      return lock();
    case "search":
      return search(request.query, request);
    case "timeline":
      return timeline(request);
    case "archived":
      return (await getStore()).archived();
    case "tags":
      return (await getStore()).allTags();
    case "capture": {
      const store = await getStore();
      const row = store.getCapture(request.id);
      if (!row) throw new Error("not-found");
      return {
        id: row.id,
        title: row.title ?? "",
        text: row.text ?? "",
        ocr_text: row.ocr_text ?? "",
        url: row.url,
        type: row.type,
        created_at: row.created_at,
        favorite: row.favorite === 1,
        archived: row.archived === 1,
        tags: store.tagsFor(row.id),
        ...(row.thumbnail ? { thumbnail_b64: bytesToBase64(row.thumbnail) } : {}),
      };
    }
    case "save-page":
      return saveActivePage();
    case "new-capture":
      return { id: await saveTypedCapture(request.text, request.tags, request.favorite) };
    case "set-favorite":
      return { updated: await (await getStore()).setFavorite(request.id, request.favorite) };
    case "set-archived":
      return { updated: await (await getStore()).setArchived(request.id, request.archived) };
    case "archive-many":
      return { count: await (await getStore()).archiveMany(request.ids) };
    case "restore-many":
      return { count: await (await getStore()).restoreMany(request.ids) };
    case "add-tag":
      return (await getStore()).addTag(request.id, request.tag);
    case "remove-tag":
      return (await getStore()).removeTag(request.id, request.tag);
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
