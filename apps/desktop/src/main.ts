import {
  app,
  BrowserWindow,
  clipboard,
  globalShortcut,
  ipcMain,
  Menu,
  nativeImage,
  Notification,
  safeStorage,
  shell,
  Tray,
} from "electron";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import type { CapturePayload, CaptureType, SearchResult, SyncReport } from "@recall/shared-types";
import { base64ToBytes, bytesToBase64 } from "@recall/crypto-core";
import type { SynonymIndex } from "@recall/search-core";
import { CaptureStore } from "./lib/db.js";
import { EncryptedPersistence } from "./lib/encrypted-persistence.js";
import { FilePersistence } from "./lib/file-persistence.js";
import { loadConfig, saveConfig, type SecretBox, type StoredConfig } from "./lib/config.js";
import { createIdentity, unlockIdentity } from "./lib/identity.js";
import { RelayClient } from "./lib/relay-client.js";
import { RELAY_SECRET, RELAY_URL } from "./lib/relay-defaults.js";
import { syncNow } from "./lib/sync.js";
import { loadSynonyms } from "./lib/synonyms.js";
import type {
  CaptureDetail,
  CaptureResult,
  IpcResult,
  SearchInput,
  SetupInput,
  StatusData,
  SyncResult,
  TimelineInput,
} from "./lib/messages.js";

const here = dirname(fileURLToPath(import.meta.url));
const userData = app.getPath("userData");
const configFile = join(userData, "config.json");
const databaseFile = join(userData, "recall.db.enc");
const synonymsFile = join(here, "synonyms.json");

const CAPTURE_SHORTCUT = "CommandOrControl+Shift+R";
const SEARCH_SHORTCUT = "CommandOrControl+Shift+S";
const SYNC_INTERVAL_MS = 30_000;
const smoke = process.argv.includes("--smoke");

let window: BrowserWindow | null = null;
let tray: Tray | null = null;
let quitting = false;
let key: CryptoKey | null = null;
let storePromise: Promise<CaptureStore> | null = null;
let configCache: StoredConfig | null = null;
let secretBox: SecretBox;
let synonyms: SynonymIndex | null = null;
let syncPromise: Promise<SyncReport> | null = null;
let syncTimer: NodeJS.Timeout | null = null;
let lastSyncAt: number | null = null;
let lastSyncError: string | null = null;

/* ------------------------------------------------------------------ */
/* state                                                               */
/* ------------------------------------------------------------------ */

function makeSecretBox(): SecretBox {
  let available = false;
  try {
    available = safeStorage.isEncryptionAvailable();
  } catch {
    available = false;
  }
  return {
    available,
    encrypt: (plaintext) => safeStorage.encryptString(plaintext).toString("base64"),
    decrypt: (token) => safeStorage.decryptString(Buffer.from(token, "base64")),
  };
}

async function getConfig(): Promise<StoredConfig> {
  configCache ??= await loadConfig(configFile, secretBox);
  return configCache;
}

async function persistConfig(next: StoredConfig): Promise<void> {
  configCache = next;
  await saveConfig(configFile, next, secretBox);
}

function requireKey(): CryptoKey {
  if (!key) throw new Error("locked");
  return key;
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
  if (window && !window.isDestroyed()) window.webContents.send("recall:changed");
  void getConfig()
    .then(async (config) => {
      if (!key) {
        tray?.setToolTip("Recall — locked");
        return;
      }
      try {
        const store = await getStore();
        tray?.setToolTip(`Recall — ${store.count()} captures${config.localOnly ? "" : " · synced"}`);
      } catch {
        tray?.setToolTip("Recall");
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
  const id = randomUUID();
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

const execFileAsync = promisify(execFile);

/** Read the Windows CF_HTML payload, which can carry the source page URL. */
function readClipboardHtml(): string {
  try {
    const buffer = clipboard.readBuffer("HTML Format");
    if (buffer.length > 0) return buffer.toString("utf8");
  } catch {
    // Some platforms expose only text/html through readHTML().
  }
  try {
    return clipboard.readHTML();
  } catch {
    return "";
  }
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

/**
 * Windows does not put an app name in the clipboard. Ask the OS for the clipboard owner's
 * process, so a capture from Notepad/Slack/Word can still say where it came from. This is
 * best-effort and deliberately short-lived; it never blocks a capture for long.
 */
async function clipboardOwnerApp(): Promise<string | null> {
  if (process.platform !== "win32") return null;
  const script = `
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class RecallClipboardOwner {
  [DllImport("user32.dll")] public static extern IntPtr GetClipboardOwner();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
}
'@
$h = [RecallClipboardOwner]::GetClipboardOwner()
if ($h -eq [IntPtr]::Zero) { return }
$procId = [uint32]0
[RecallClipboardOwner]::GetWindowThreadProcessId($h, [ref]$procId) | Out-Null
if ($procId -eq 0) { return }
$p = Get-Process -Id $procId -ErrorAction SilentlyContinue
if ($p) { $p.ProcessName }
`;
  try {
    const { stdout } = await execFileAsync(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script],
      { timeout: 1500, windowsHide: true },
    );
    const name = stdout.trim().replace(/\.exe$/i, "");
    return name.length > 0 ? name : null;
  } catch {
    return null;
  }
}

async function captureClipboard(): Promise<CaptureResult> {
  if (!key) {
    notify("Recall is locked", "Open Recall and unlock to capture.");
    return { saved: false, reason: "locked" };
  }

  const text = clipboard.readText().trim();
  if (text.length > 0) {
    const isUrl = /^https?:\/\/\S+$/i.test(text);
    const sourceUrl = sourceUrlFromHtml(readClipboardHtml()) ?? (isUrl ? text : firstUrlIn(text));
    const sourceHost = sourceUrl ? hostOf(sourceUrl) : null;
    const app = await clipboardOwnerApp();
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
    notify("Saved to Recall", preview(payload));
    return { saved: true, kind: payload.type, id };
  }

  const image = clipboard.readImage();
  if (!image.isEmpty()) {
    const size = image.getSize();
    const scale = Math.min(1, 256 / Math.max(size.width, size.height));
    const resized =
      scale < 1
        ? image.resize({
            width: Math.max(1, Math.round(size.width * scale)),
            height: Math.max(1, Math.round(size.height * scale)),
            quality: "good",
          })
        : image;
    const app = await clipboardOwnerApp();
    const payload: CapturePayload = {
      type: "screenshot",
      title: app ? `Image from ${app}` : "Clipboard image",
      thumbnail_b64: resized.toPNG().toString("base64"),
    };
    const id = await saveCapture(payload);
    notify("Screenshot saved to Recall", "Stored locally as an encrypted thumbnail.");
    return { saved: true, kind: "screenshot", id };
  }

  notify("Nothing to capture", "The clipboard has no text or image.");
  return { saved: false, reason: "empty-clipboard" };
}

function preview(payload: CapturePayload): string {
  const value = payload.title || payload.text || payload.url || "";
  const flattened = value.replace(/\s+/g, " ").trim();
  return flattened.length > 120 ? `${flattened.slice(0, 117)}…` : flattened || "Captured.";
}

function notify(title: string, body: string): void {
  if (!Notification.isSupported()) return;
  try {
    new Notification({ title, body }).show();
  } catch {
    // Notifications are best-effort (some Linux sessions have no daemon).
  }
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
/* window + tray                                                       */
/* ------------------------------------------------------------------ */

function createWindow(): void {
  window = new BrowserWindow({
    width: 1100,
    height: 760,
    minWidth: 720,
    minHeight: 560,
    show: false,
    backgroundColor: "#070a12",
    title: "Recall",
    icon: join(here, "assets", "icon.png"),
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(here, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  void window.loadFile(join(here, "renderer", "index.html"));
  window.once("ready-to-show", () => window?.show());
  window.on("close", (event) => {
    if (quitting) return;
    event.preventDefault();
    window?.hide();
  });
  window.on("closed", () => {
    window = null;
  });
}

function showWindow(focusSearch = false): void {
  if (!window || window.isDestroyed()) {
    createWindow();
    return;
  }
  if (window.isMinimized()) window.restore();
  window.show();
  window.focus();
  if (focusSearch) window.webContents.send("recall:focus-search");
}

function createTray(): void {
  const image = nativeImage.createFromPath(join(here, "assets", "tray.png"));
  tray = new Tray(image.isEmpty() ? nativeImage.createEmpty() : image);
  tray.setToolTip("Recall");
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: "Open Recall", click: () => showWindow() },
      {
        label: "Capture clipboard",
        accelerator: CAPTURE_SHORTCUT,
        click: () => void captureClipboard().catch(() => undefined),
      },
      {
        label: "Sync now",
        click: () => {
          void runSync()
            .then(() => broadcastChanged())
            .catch(() => undefined);
        },
      },
      { type: "separator" },
      {
        label: "Lock",
        click: () => {
          void lock().catch(() => undefined);
        },
      },
      { type: "separator" },
      {
        label: "Quit Recall",
        click: () => {
          quitting = true;
          app.quit();
        },
      },
    ]),
  );
  tray.on("click", () => showWindow());
}

function registerShortcuts(): void {
  if (!globalShortcut.register(CAPTURE_SHORTCUT, () => void captureClipboard().catch(() => undefined))) {
    console.warn(`[recall] could not register ${CAPTURE_SHORTCUT}; another app may own it`);
  }
  if (!globalShortcut.register(SEARCH_SHORTCUT, () => showWindow(true))) {
    console.warn(`[recall] could not register ${SEARCH_SHORTCUT}; another app may own it`);
  }
}

/* ------------------------------------------------------------------ */
/* IPC                                                                 */
/* ------------------------------------------------------------------ */

function handle<T, A = unknown>(channel: string, fn: (argument: A) => Promise<T>): void {
  ipcMain.handle(channel, async (_event, argument: unknown) => {
    try {
      return { ok: true, data: await fn(argument as A) } satisfies IpcResult<T>;
    } catch (error) {
      return { ok: false, error: safeError(error) } satisfies IpcResult<T>;
    }
  });
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
    await shell.openExternal(parsed.toString());
  });
  handle("recall:quit", async () => {
    quitting = true;
    app.quit();
  });
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
    const { MemoryPersistence } = await import("./lib/db.js");
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

    if (!window) throw new Error("window-missing");
    if (window.webContents.isLoading()) {
      await new Promise<void>((resolve) => window!.webContents.once("did-finish-load", () => resolve()));
    }
    const bridge = await window.webContents.executeJavaScript("typeof window.recall === 'object'");
    if (bridge !== true) throw new Error("preload-bridge-missing");
    const rendererReady = await window.webContents.executeJavaScript(
      "document.documentElement.dataset.ready === '1'",
    );
    if (rendererReady !== true) throw new Error("renderer-script-missing");
    console.log(`[smoke] ok store=${count} results=${hits.length} bridge=${String(bridge)} renderer=${String(rendererReady)}`);
    quitting = true;
    app.exit(0);
  } catch (error) {
    console.error("[smoke] failed", error);
    quitting = true;
    app.exit(1);
  }
}

/* ------------------------------------------------------------------ */
/* lifecycle                                                           */
/* ------------------------------------------------------------------ */

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => showWindow());

  app.whenReady().then(
    async () => {
      app.setAppUserModelId("com.recall.desktop");
      secretBox = makeSecretBox();
      configCache = await loadConfig(configFile, secretBox);
      registerIpc();
      createWindow();
      if (smoke) {
        await runSmoke();
        return;
      }
      createTray();
      registerShortcuts();
      startSyncTimer();
    },
    (error: unknown) => {
      console.error("[recall] failed to start", error);
      app.quit();
    },
  );

  app.on("activate", () => showWindow());

  // Tray app: closing the window hides it; the process stays alive until Quit.
  app.on("window-all-closed", () => undefined);

  app.on("before-quit", () => {
    quitting = true;
    globalShortcut.unregisterAll();
    if (syncTimer) {
      clearInterval(syncTimer);
      syncTimer = null;
    }
    resetStore();
  });
}
