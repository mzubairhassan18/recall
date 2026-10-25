import type { CaptureType, SearchResult } from "@recall/shared-types";
import type { CaptureDetail, StatusData } from "../lib/messages.js";
import type { RecallBridge } from "../preload.js";

declare global {
  interface Window {
    recall: RecallBridge;
  }
}

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const setupPanel = $("setup-panel");
const unlockPanel = $("unlock-panel");
const mainPanel = $("main-panel");
const tagline = $("tagline");
const modePill = $("mode-pill");
const resultsEl = $("results");
const emptyEl = $("empty");
const searchInput = $<HTMLInputElement>("search");
const filtersEl = $("filters");
const typeFilter = $<HTMLSelectElement>("filter-type");
const domainFilter = $<HTMLInputElement>("filter-domain");
const syncInfo = $("sync-info");
const detailEl = $("detail");
const detailTitle = $("detail-title");
const detailMeta = $("detail-meta");
const detailText = $("detail-text");
const detailImage = $<HTMLImageElement>("detail-image");
const detailOpen = $<HTMLButtonElement>("detail-open");

let status: StatusData | null = null;
let mode: "search" | "timeline" = "search";
let searchTimer: number | undefined;
let statusTimer: number | undefined;
let changeTimer: number | undefined;
let detailUrl: string | null = null;

const ERROR_TEXT: Record<string, string> = {
  "passphrase-too-short": "Use at least 8 characters — 4–6 diceware words is ideal.",
  "wrong-passphrase": "That passphrase does not unlock this device.",
  "not-configured": "Set up Recall first.",
  "already-configured": "This device is already configured.",
  "invalid-relay-url": "The relay URL must start with http:// or https://.",
  "relay-secret-required": "A relay secret is required when a relay URL is set.",
  "invalid-url": "That link is not a valid web address.",
  network: "Relay unreachable. Check the URL, secret, and connection.",
  locked: "Unlock Recall first.",
  "not-found": "That capture is no longer available.",
  "request-failed": "Something went wrong. Try again.",
  "sync-failed": "Sync failed. Check the relay settings.",
};

function messageFor(code: string): string {
  return ERROR_TEXT[code] ?? ERROR_TEXT["request-failed"]!;
}

/* ------------------------------------------------------------------ */
/* panels                                                              */
/* ------------------------------------------------------------------ */

function showPanel(panel: HTMLElement): void {
  for (const section of [setupPanel, unlockPanel, mainPanel]) {
    section.hidden = section !== panel;
  }
}

function applyStatus(next: StatusData): void {
  status = next;
  tagline.textContent = next.configured
    ? `${next.count} ${next.count === 1 ? "capture" : "captures"}${next.localOnly ? " · offline only" : " · encrypted sync"}`
    : "Your encrypted memory";

  modePill.hidden = !next.configured;
  modePill.textContent = next.localOnly ? "Offline only" : "Relay";
  modePill.classList.toggle("online", !next.localOnly);

  if (!next.configured) {
    showPanel(setupPanel);
    $<HTMLInputElement>("setup-passphrase").focus();
    return;
  }
  if (next.locked) {
    showPanel(unlockPanel);
    $<HTMLInputElement>("unlock-passphrase").focus();
    return;
  }

  showPanel(mainPanel);
  renderSyncInfo(next);
  void refresh();
}

function renderSyncInfo(next: StatusData): void {
  if (next.syncing) {
    syncInfo.textContent = "syncing…";
    return;
  }
  if (next.lastSyncError) {
    syncInfo.textContent = messageFor(next.lastSyncError);
    return;
  }
  if (next.localOnly) {
    syncInfo.textContent = "local only";
    return;
  }
  syncInfo.textContent = next.lastSyncAt ? `synced ${relative(next.lastSyncAt)}` : "not synced yet";
}

async function refreshStatus(): Promise<void> {
  const next = await window.recall.status();
  applyStatus(next);
}

function scheduleRefresh(): void {
  window.clearTimeout(changeTimer);
  changeTimer = window.setTimeout(() => {
    void refreshStatus().catch(() => undefined);
  }, 120);
}

/* ------------------------------------------------------------------ */
/* results                                                             */
/* ------------------------------------------------------------------ */

async function refresh(): Promise<void> {
  if (!status || status.locked) return;
  const query = searchInput.value.trim();

  try {
    let rows: SearchResult[];
    if (mode === "search" && query.length > 0) {
      rows = await window.recall.search({ query });
    } else {
      rows = await window.recall.timeline({
        captureType: (typeFilter.value || undefined) as CaptureType | undefined,
        domain: domainFilter.value.trim() || undefined,
      });
    }
    renderResults(rows, mode === "search" ? query : "");
  } catch (error) {
    renderResults([], "");
    if (error instanceof Error && error.message === "locked") await refreshStatus();
  }
}

function renderResults(rows: SearchResult[], query: string): void {
  resultsEl.replaceChildren();
  emptyEl.hidden = rows.length > 0;
  if (rows.length === 0) {
    emptyEl.textContent = query
      ? "Nothing matched — the fallback should have caught that. Try another word."
      : "No captures yet. Copy text, then press Ctrl+Shift+R.";
    return;
  }

  for (const row of rows) {
    const item = document.createElement("li");
    item.className = "result";
    item.tabIndex = 0;

    if (row.thumbnail_b64) {
      const image = document.createElement("img");
      image.className = "thumb";
      image.alt = "";
      image.src = `data:image/png;base64,${row.thumbnail_b64}`;
      item.appendChild(image);
    }

    const body = document.createElement("div");
    body.className = "body";

    const title = document.createElement("div");
    title.className = "title";
    title.textContent = row.title;
    body.appendChild(title);

    const snippet = document.createElement("div");
    snippet.className = "snippet";
    snippet.innerHTML = highlight(row.snippet, query);
    body.appendChild(snippet);

    const meta = document.createElement("div");
    meta.className = "meta";
    const domain = domainOf(row.url);
    if (domain) {
      const domainEl = document.createElement("span");
      domainEl.textContent = domain;
      meta.appendChild(domainEl);
    }
    const time = document.createElement("span");
    time.textContent = relative(row.created_at);
    if (Date.now() - row.created_at > 365 * 86_400_000) time.className = "faded";
    meta.appendChild(time);
    const kind = document.createElement("span");
    kind.textContent = row.type;
    meta.appendChild(kind);
    body.appendChild(meta);

    item.appendChild(body);
    item.addEventListener("click", () => void openResult(row));
    item.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        void openResult(row);
      }
    });
    resultsEl.appendChild(item);
  }
}

async function openResult(row: SearchResult): Promise<void> {
  if (row.type === "url" && row.url) {
    try {
      await window.recall.openExternal(row.url);
    } catch {
      // Invalid links stay in the list; nothing to do.
    }
    return;
  }
  try {
    const detail = await window.recall.detail(row.id);
    showDetail(detail);
  } catch {
    // The row may have been removed between render and click.
  }
}

function showDetail(detail: CaptureDetail): void {
  detailTitle.textContent = detail.title || "(untitled)";
  detailMeta.textContent = `${detail.type} · ${new Date(detail.created_at).toLocaleString()}`;
  detailText.textContent = detail.text || detail.ocr_text || "(no text)";
  if (detail.thumbnail_b64) {
    detailImage.src = `data:image/png;base64,${detail.thumbnail_b64}`;
    detailImage.hidden = false;
  } else {
    detailImage.hidden = true;
    detailImage.removeAttribute("src");
  }
  detailUrl = detail.url;
  detailOpen.hidden = !detailUrl;
  detailEl.hidden = false;
}

/* ------------------------------------------------------------------ */
/* forms                                                               */
/* ------------------------------------------------------------------ */

$("setup-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const errorEl = $("setup-error");
  const submit = $<HTMLButtonElement>("setup-submit");
  errorEl.hidden = true;

  const passphrase = $<HTMLInputElement>("setup-passphrase").value;

  submit.disabled = true;
  submit.textContent = "Deriving key…";
  try {
    const next = await window.recall.setup({ passphrase });
    $<HTMLInputElement>("setup-passphrase").value = "";
    applyStatus(next);
    searchInput.focus();
  } catch (error) {
    errorEl.textContent = messageFor(error instanceof Error ? error.message : "request-failed");
    errorEl.hidden = false;
  } finally {
    submit.disabled = false;
    submit.textContent = "Create encrypted store";
  }
});

$("unlock-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const errorEl = $("unlock-error");
  const submit = $<HTMLButtonElement>("unlock-submit");
  errorEl.hidden = true;

  const passphrase = $<HTMLInputElement>("unlock-passphrase").value;
  submit.disabled = true;
  submit.textContent = "Deriving key…";
  try {
    const next = await window.recall.unlock(passphrase);
    $<HTMLInputElement>("unlock-passphrase").value = "";
    applyStatus(next);
    searchInput.focus();
  } catch (error) {
    errorEl.textContent = messageFor(error instanceof Error ? error.message : "request-failed");
    errorEl.hidden = false;
  } finally {
    submit.disabled = false;
    submit.textContent = "Unlock";
  }
});

/* ------------------------------------------------------------------ */
/* main controls                                                       */
/* ------------------------------------------------------------------ */

function setMode(next: "search" | "timeline"): void {
  mode = next;
  $("tab-search").classList.toggle("active", next === "search");
  $("tab-timeline").classList.toggle("active", next === "timeline");
  filtersEl.hidden = next === "search";
  searchInput.placeholder = next === "search" ? "Search your memory…" : "Filter the timeline…";
  void refresh();
}

$("tab-search").addEventListener("click", () => setMode("search"));
$("tab-timeline").addEventListener("click", () => setMode("timeline"));

searchInput.addEventListener("input", () => {
  if (mode !== "search") setMode("search");
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => void refresh(), 140);
});

typeFilter.addEventListener("change", () => void refresh());
domainFilter.addEventListener("input", () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => void refresh(), 200);
});

$("save-clipboard").addEventListener("click", async () => {
  const button = $<HTMLButtonElement>("save-clipboard");
  button.disabled = true;
  try {
    const result = await window.recall.saveClipboard();
    button.textContent = result.saved ? "Saved ✓" : "Nothing to save";
    setTimeout(() => (button.textContent = "Capture clipboard"), 1400);
    if (result.saved) {
      await refreshStatus();
      await refresh();
    }
  } catch {
    button.textContent = "Could not save";
    setTimeout(() => (button.textContent = "Capture clipboard"), 1600);
  } finally {
    button.disabled = false;
  }
});

$("sync-now").addEventListener("click", async () => {
  const button = $<HTMLButtonElement>("sync-now");
  button.disabled = true;
  button.textContent = "Syncing…";
  try {
    await window.recall.sync();
    await refreshStatus();
    await refresh();
  } catch {
    syncInfo.textContent = "sync failed";
  } finally {
    button.disabled = false;
    button.textContent = "Sync";
  }
});

$("lock").addEventListener("click", async () => {
  const next = await window.recall.lock();
  applyStatus(next);
});

$("quit").addEventListener("click", () => {
  void window.recall.quit();
});

$("detail-close").addEventListener("click", () => {
  detailEl.hidden = true;
});

detailOpen.addEventListener("click", () => {
  if (detailUrl) void window.recall.openExternal(detailUrl).catch(() => undefined);
});

detailEl.addEventListener("click", (event) => {
  if (event.target === detailEl) detailEl.hidden = true;
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") detailEl.hidden = true;
});

window.recall.onChanged(scheduleRefresh);
window.recall.onFocusSearch(() => {
  if (!status || status.locked || !status.configured) return;
  setMode("search");
  searchInput.focus();
  searchInput.select();
});

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

function relative(timestamp: number): string {
  const delta = Math.max(0, Date.now() - timestamp);
  const minute = 60_000;
  const hour = 3_600_000;
  const day = 86_400_000;
  if (delta < minute) return "just now";
  if (delta < hour) return `${Math.floor(delta / minute)}m ago`;
  if (delta < day) return `${Math.floor(delta / hour)}h ago`;
  if (delta < 7 * day) return `${Math.floor(delta / day)}d ago`;
  if (delta < 30 * day) return `${Math.floor(delta / (7 * day))}w ago`;
  if (delta < 365 * day) return `${Math.floor(delta / (30 * day))}mo ago`;
  return `${Math.floor(delta / (365 * day))}y ago`;
}

function domainOf(url: string | undefined): string {
  if (!url) return "";
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

function highlight(text: string, query: string): string {
  const escaped = escapeHtml(text);
  const terms = query
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((term) => term.length > 1);
  if (terms.length === 0) return escaped;
  const pattern = new RegExp(`(${terms.map(escapeRegExp).join("|")})`, "giu");
  return escaped.replace(pattern, "<mark>$1</mark>");
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/* ------------------------------------------------------------------ */
/* boot                                                                */
/* ------------------------------------------------------------------ */

void (async () => {
  try {
    const next = await window.recall.status();
    applyStatus(next);
  } catch {
    showPanel(unlockPanel);
  }
  statusTimer = window.setInterval(() => {
    if (status?.configured) void refreshStatus().catch(() => undefined);
  }, 15_000);
})();

window.addEventListener("beforeunload", () => {
  window.clearInterval(statusTimer);
});

document.documentElement.dataset.ready = "1";
