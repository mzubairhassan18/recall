import type { CaptureType } from "@recall/shared-types";
import type { CaptureSummary } from "../lib/db.js";
import type {
  ArchiveResult,
  CaptureDetail,
  CaptureQuery,
  MutationResult,
  NewCaptureResult,
  PopupRequest,
  PopupResponse,
  StatusData,
} from "../lib/messages.js";

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) {
  document.querySelectorAll<SVGSVGElement>("svg").forEach((svg) => svg.pauseAnimations());
}

const setupPanel = $("setup-panel");
const unlockPanel = $("unlock-panel");
const mainPanel = $("main-panel");
const tagline = $("tagline");
const modePill = $("mode-pill");
const resultsEl = $("results");
const emptyEl = $("empty");
const emptyTitleEl = $("empty-title");
const emptyCopyEl = $("empty-copy");
const searchInput = $<HTMLInputElement>("search");
const typeFilter = $<HTMLSelectElement>("filter-type");
const domainFilter = $<HTMLInputElement>("filter-domain");
const favoriteFilter = $<HTMLButtonElement>("favorite-filter");
const sortToggle = $<HTMLButtonElement>("sort-toggle");
const sortLabel = $("sort-label");
const resultCount = $("result-count");
const tagFilters = $("tag-filters");
const viewToggle = $<HTMLButtonElement>("view-toggle");
const selectToggle = $<HTMLButtonElement>("select-toggle");
const selectionBar = $("selection-bar");
const selectionCount = $("selection-count");
const selectionArchive = $<HTMLButtonElement>("selection-archive");
const selectionCancel = $<HTMLButtonElement>("selection-cancel");
const footer = $("footer");
const securityOpen = $<HTMLButtonElement>("security-open");
const archiveOpen = $<HTMLButtonElement>("archive-open");
const archiveCount = $("archive-count");
const syncInfo = $("sync-info");
const pinBanner = $("pin-banner");
const pinBannerOpen = $<HTMLButtonElement>("pin-banner-open");

const securityEl = $("security");
const securityHint = $("security-hint");
const pinForm = $<HTMLFormElement>("pin-form");
const pinFormLabel = $("pin-form-label");
const pinNew = $<HTMLInputElement>("pin-new");
const pinConfirm = $<HTMLInputElement>("pin-confirm");
const pinError = $("pin-error");
const pinSave = $<HTMLButtonElement>("pin-save");
const pinRemove = $<HTMLButtonElement>("pin-remove");

const unlockHint = $("unlock-hint");
const unlockMeta = $("unlock-meta");
const unlockError = $("unlock-error");
const unlockPinForm = $<HTMLFormElement>("unlock-pin-form");
const unlockPassphraseForm = $<HTMLFormElement>("unlock-passphrase-form");
const unlockPin = $<HTMLInputElement>("unlock-pin");
const unlockPassphrase = $<HTMLInputElement>("unlock-passphrase");
const unlockPinSubmit = $<HTMLButtonElement>("unlock-pin-submit");
const unlockPassphraseSubmit = $<HTMLButtonElement>("unlock-passphrase-submit");
const unlockUsePassphrase = $<HTMLButtonElement>("unlock-use-passphrase");
const unlockUsePin = $<HTMLButtonElement>("unlock-use-pin");

const detailEl = $("detail");
const detailTitle = $("detail-title");
const detailMeta = $("detail-meta");
const detailText = $("detail-text");
const detailImage = $<HTMLImageElement>("detail-image");
const detailFavorite = $<HTMLButtonElement>("detail-favorite");
const detailArchive = $<HTMLButtonElement>("detail-archive");
const detailOpen = $<HTMLButtonElement>("detail-open");
const detailTags = $("detail-tags");
const detailTagForm = $<HTMLFormElement>("detail-tag-form");
const detailTagInput = $<HTMLInputElement>("detail-tag-input");

const composerEl = $("composer");
const composerForm = $<HTMLFormElement>("composer-form");
const composerText = $<HTMLTextAreaElement>("composer-text");
const composerTags = $<HTMLInputElement>("composer-tags");
const composerFavorite = $<HTMLInputElement>("composer-favorite");
const composerError = $("composer-error");
const composerSubmit = $<HTMLButtonElement>("composer-submit");

const archiveEl = $("archive");
const archiveResults = $("archive-results");
const archiveEmpty = $("archive-empty");

let status: StatusData | null = null;
let view: "grid" | "timeline" = "grid";
let descending = true;
let favoritesOnly = false;
let selectedTag: string | null = null;
let allTags: string[] = [];
let selectionMode = false;
const selectedIds = new Set<string>();
let currentRows: CaptureSummary[] = [];
let currentDetail: CaptureDetail | null = null;
let searchTimer: number | undefined;
let refreshTimer: number | undefined;
let lastQuery = "";
let refreshInFlight = false;
let refreshAgain = false;

const ERROR_TEXT: Record<string, string> = {
  "passphrase-too-short": "Use at least 8 characters — 4–6 diceware words is ideal.",
  "wrong-passphrase": "That passphrase does not unlock this device.",
  "not-configured": "Set up Recall first.",
  "already-configured": "This device is already configured.",
  "invalid-relay-url": "The relay URL must start with http:// or https://.",
  "relay-secret-required": "A relay secret is required when a relay URL is set.",
  "network": "Relay unreachable. Check the URL, secret, and connection.",
  "locked": "Unlock Recall first.",
  "no-active-tab": "There is no active page to save.",
  "empty-capture": "Write something first.",
  "not-found": "That capture is no longer available.",
  "request-failed": "Something went wrong. Try again.",
  "sync-failed": "Sync failed. Check the relay settings.",
  "invalid-pin": "Choose a 4-digit PIN.",
  "pin-not-configured": "No PIN is set for this browser. Unlock with your passphrase.",
  "wrong-pin": "That PIN is not correct. Try again.",
  "pin-locked": "Too many attempts. Wait a moment, or use your passphrase.",
};

function messageFor(code: string): string {
  return ERROR_TEXT[code] ?? ERROR_TEXT["request-failed"]!;
}

async function send<T>(request: PopupRequest): Promise<T> {
  const response = (await chrome.runtime.sendMessage(request)) as PopupResponse;
  if (!response || !response.ok) throw new Error(response?.error ?? "request-failed");
  return response.data as T;
}

/* ------------------------------------------------------------------ */
/* panels                                                              */
/* ------------------------------------------------------------------ */

function setButtonLabel(button: HTMLButtonElement, label: string): void {
  const target = button.querySelector<HTMLElement>(".btn-label");
  if (target) target.textContent = label;
  else button.textContent = label;
}

function showPanel(panel: HTMLElement): void {
  for (const section of [setupPanel, unlockPanel, mainPanel]) {
    section.hidden = section !== panel;
  }
}

function closeOverlays(): void {
  composerEl.hidden = true;
  detailEl.hidden = true;
  archiveEl.hidden = true;
  securityEl.hidden = true;
}

function pinLockoutText(until: number | null): string | null {
  if (!until || until <= Date.now()) return null;
  const seconds = Math.max(1, Math.ceil((until - Date.now()) / 1000));
  return `Too many attempts. Try again in ${seconds}s, or use your passphrase.`;
}

function setUnlockMode(mode: "pin" | "passphrase"): void {
  const pinAvailable = status?.pinConfigured === true;
  const usePin = pinAvailable && mode === "pin";
  unlockPinForm.hidden = !usePin;
  unlockPassphraseForm.hidden = usePin;
  unlockUsePassphrase.hidden = !usePin;
  unlockUsePin.hidden = !pinAvailable || usePin;
  unlockHint.textContent = usePin
    ? "Enter your 4-digit PIN to continue."
    : "Your captures are encrypted on this device. Enter the passphrase to continue.";
  const lockout = pinLockoutText(status?.pinLockedUntil ?? null);
  unlockMeta.textContent = lockout
    ? lockout
    : usePin
      ? "PIN unlock is local to this browser. Use your passphrase if you forget it."
      : pinAvailable
        ? "You can switch back to the PIN at any time."
        : "";
  unlockError.hidden = true;
  if (usePin) unlockPin.focus();
  else unlockPassphrase.focus();
}

function applyStatus(next: StatusData): void {
  status = next;
  const captures = `${next.count} ${next.count === 1 ? "capture" : "captures"}`;
  tagline.textContent =
    next.archivedCount > 0
      ? `${captures} · ${next.archivedCount} archived`
      : next.configured
        ? captures
        : "Your encrypted memory";

  modePill.hidden = !next.configured;
  $("mode-label").textContent = next.localOnly ? "Offline only" : "Relay";
  modePill.classList.toggle("online", !next.localOnly);

  const unlocked = next.configured && !next.locked;
  archiveOpen.hidden = !unlocked;
  securityOpen.hidden = !unlocked;
  pinBanner.hidden = !unlocked || next.pinConfigured;
  archiveCount.hidden = next.archivedCount === 0;
  archiveCount.textContent = next.archivedCount > 99 ? "99+" : String(next.archivedCount);

  if (!next.configured) {
    closeOverlays();
    showPanel(setupPanel);
    $<HTMLInputElement>("setup-passphrase").focus();
    return;
  }
  if (next.locked) {
    closeOverlays();
    setSelectionMode(false);
    showPanel(unlockPanel);
    setUnlockMode(next.pinConfigured ? "pin" : "passphrase");
    return;
  }

  showPanel(mainPanel);
  renderSyncInfo(next);
  void refresh();
  if (!refreshTimer) {
    refreshTimer = window.setInterval(() => void backgroundTick(), 30_000);
  }
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
  const next = await send<StatusData>({ type: "status" });
  applyStatus(next);
}

async function backgroundTick(): Promise<void> {
  if (!status || status.locked || !status.configured) return;
  try {
    await send({ type: "sync" });
    await refreshStatus();
  } catch {
    await refreshStatus();
  }
}

/* ------------------------------------------------------------------ */
/* results                                                             */
/* ------------------------------------------------------------------ */

function currentFilter(): CaptureQuery {
  return {
    captureType: (typeFilter.value || undefined) as CaptureType | undefined,
    domain: domainFilter.value.trim() || undefined,
    favoritesOnly: favoritesOnly || undefined,
    tag: selectedTag ?? undefined,
    descending,
  };
}

async function refresh(): Promise<void> {
  if (!status || status.locked) return;
  if (refreshInFlight) {
    refreshAgain = true;
    return;
  }
  refreshInFlight = true;
  try {
    const query = searchInput.value.trim();
    lastQuery = query;
    const filter = currentFilter();
    let rows: CaptureSummary[];
    try {
      rows = query
        ? await send<CaptureSummary[]>({ type: "search", query, ...filter })
        : await send<CaptureSummary[]>({ type: "timeline", ...filter });
    } catch (error) {
      renderResults([], "");
      if (error instanceof Error && error.message === "locked") await refreshStatus();
      return;
    }

    if (selectionMode) {
      const visible = new Set(rows.map((row) => row.id));
      for (const id of [...selectedIds]) {
        if (!visible.has(id)) selectedIds.delete(id);
      }
    }
    renderResults(rows, query);
    const clearedTag = await refreshTags();
    if (clearedTag) refreshAgain = true;
  } finally {
    refreshInFlight = false;
    if (refreshAgain) {
      refreshAgain = false;
      void refresh();
    }
  }
}

async function refreshTags(): Promise<boolean> {
  try {
    const tags = await send<string[]>({ type: "tags" });
    allTags = tags;
    let cleared = false;
    if (selectedTag && !tags.some((tag) => tag.toLowerCase() === selectedTag!.toLowerCase())) {
      selectedTag = null;
      cleared = true;
    }
    renderTagFilters();
    return cleared;
  } catch {
    return false;
  }
}

function renderTagFilters(): void {
  tagFilters.replaceChildren();
  tagFilters.hidden = allTags.length === 0;
  if (allTags.length === 0) return;

  const all = document.createElement("button");
  all.type = "button";
  all.className = "tag-chip";
  all.textContent = "All tags";
  all.setAttribute("aria-pressed", String(selectedTag === null));
  all.addEventListener("click", () => {
    selectedTag = null;
    renderTagFilters();
    void refresh();
  });
  tagFilters.appendChild(all);

  for (const tag of allTags) {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "tag-chip";
    chip.textContent = tag;
    chip.setAttribute("aria-pressed", String(selectedTag?.toLowerCase() === tag.toLowerCase()));
    chip.addEventListener("click", () => {
      selectedTag = selectedTag?.toLowerCase() === tag.toLowerCase() ? null : tag;
      renderTagFilters();
      void refresh();
    });
    tagFilters.appendChild(chip);
  }
}

function renderResults(rows: CaptureSummary[], query: string): void {
  currentRows = rows;
  resultsEl.replaceChildren();
  resultsEl.classList.toggle("timeline", view === "timeline");
  resultsEl.classList.toggle("selecting", selectionMode);
  resultCount.textContent = `${rows.length} ${rows.length === 1 ? "result" : "results"}`;

  const isEmpty = rows.length === 0;
  resultsEl.hidden = isEmpty;
  emptyEl.hidden = !isEmpty;
  if (isEmpty) {
    const hasFilters = Boolean(
      query ||
        typeFilter.value ||
        domainFilter.value.trim() ||
        favoritesOnly ||
        selectedTag,
    );
    if (query) {
      emptyTitleEl.textContent = "No matches";
      emptyCopyEl.textContent = "Try another word, or search by what it felt like.";
    } else if (favoritesOnly) {
      emptyTitleEl.textContent = "No favorites yet";
      emptyCopyEl.textContent = "Tap the star on a capture to keep it close.";
    } else if (selectedTag) {
      emptyTitleEl.textContent = `No captures tagged “${selectedTag}”`;
      emptyCopyEl.textContent = "Clear the tag filter or add that tag to a capture.";
    } else if (hasFilters) {
      emptyTitleEl.textContent = "Nothing matches";
      emptyCopyEl.textContent = "Clear a filter to see more of your memory.";
    } else {
      emptyTitleEl.textContent = "Your memory starts here";
      emptyCopyEl.textContent =
        "Select text on any page and choose “Save to Recall”, or create a new capture below.";
    }
    updateSelectionBar();
    return;
  }

  let groupLabel = "";
  for (const row of rows) {
    if (view === "timeline") {
      const label = dateLabel(row.created_at);
      if (label !== groupLabel) {
        groupLabel = label;
        const group = document.createElement("li");
        group.className = "timeline-group";
        group.textContent = label;
        resultsEl.appendChild(group);
      }
    }
    resultsEl.appendChild(renderResult(row, query));
  }
  updateSelectionBar();
}

function renderResult(row: CaptureSummary, query: string): HTMLLIElement {
  const item = document.createElement("li");
  item.className = "result";
  item.tabIndex = 0;
  item.dataset.id = row.id;
  item.classList.toggle("selected", selectionMode && selectedIds.has(row.id));

  const selectMark = document.createElement("span");
  selectMark.className = "select-mark";
  selectMark.appendChild(svgIcon("i-check"));
  item.appendChild(selectMark);

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
  title.textContent = row.title || row.snippet || "(untitled)";
  body.appendChild(title);

  const snippet = document.createElement("div");
  snippet.className = "snippet";
  snippet.innerHTML = highlight(row.snippet, query);
  body.appendChild(snippet);

  if (row.tags.length > 0) {
    const cardTags = document.createElement("div");
    cardTags.className = "card-tags";
    for (const tag of row.tags.slice(0, 3)) {
      const tagEl = document.createElement("span");
      tagEl.className = "card-tag";
      tagEl.textContent = tag;
      cardTags.appendChild(tagEl);
    }
    if (row.tags.length > 3) {
      const more = document.createElement("span");
      more.className = "card-tag";
      more.textContent = `+${row.tags.length - 3}`;
      cardTags.appendChild(more);
    }
    body.appendChild(cardTags);
  }

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
  kind.className = "kind";
  kind.textContent = row.type;
  meta.appendChild(kind);
  body.appendChild(meta);

  const favorite = document.createElement("button");
  favorite.type = "button";
  favorite.className = "fav-btn";
  favorite.classList.toggle("active", row.favorite);
  favorite.title = row.favorite ? "Remove from favorites" : "Add to favorites";
  favorite.setAttribute("aria-label", favorite.title);
  favorite.setAttribute("aria-pressed", String(row.favorite));
  favorite.appendChild(svgIcon("i-star"));
  favorite.addEventListener("click", (event) => {
    event.stopPropagation();
    void toggleFavorite(row);
  });
  item.appendChild(favorite);

  item.appendChild(body);
  item.addEventListener("click", () => {
    if (selectionMode) toggleSelection(row.id, item);
    else void openResult(row);
  });
  item.addEventListener("keydown", (event) => {
    if (event.target !== item) return;
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      if (selectionMode) toggleSelection(row.id, item);
      else void openResult(row);
    }
  });
  return item;
}

async function openResult(row: CaptureSummary): Promise<void> {
  try {
    const detail = await send<CaptureDetail>({ type: "capture", id: row.id });
    detailTagInput.value = "";
    renderDetail(detail);
    detailEl.hidden = false;
  } catch {
    // The row may have been removed between render and click.
  }
}

function renderDetail(detail: CaptureDetail): void {
  currentDetail = detail;
  detailTitle.textContent = detail.title || detail.text.split("\n")[0]?.slice(0, 90) || "(untitled)";
  detailMeta.textContent = `${detail.type} · ${new Date(detail.created_at).toLocaleString()}${
    detail.archived ? " · archived" : ""
  }`;
  detailText.textContent = detail.text || detail.ocr_text || "(no text)";
  detailOpen.hidden = !detail.url;

  detailFavorite.setAttribute("aria-pressed", String(detail.favorite));
  const favoriteLabel = detailFavorite.querySelector("span");
  if (favoriteLabel) favoriteLabel.textContent = detail.favorite ? "Favorited" : "Favorite";

  const archiveLabel = detailArchive.querySelector("span");
  if (archiveLabel) archiveLabel.textContent = detail.archived ? "Restore" : "Archive";
  setButtonIcon(detailArchive, detail.archived ? "i-restore" : "i-archive");

  if (detail.thumbnail_b64) {
    detailImage.src = `data:image/png;base64,${detail.thumbnail_b64}`;
    detailImage.hidden = false;
  } else {
    detailImage.hidden = true;
    detailImage.removeAttribute("src");
  }
  renderDetailTags(detail.tags);
}

function renderDetailTags(tags: string[]): void {
  detailTags.replaceChildren();
  for (const tag of tags) {
    const chip = document.createElement("span");
    chip.className = "detail-tag";
    const label = document.createElement("span");
    label.textContent = tag;
    chip.appendChild(label);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "tag-remove";
    remove.title = `Remove tag ${tag}`;
    remove.setAttribute("aria-label", `Remove tag ${tag}`);
    remove.textContent = "×";
    remove.addEventListener("click", () => void removeTag(tag));
    chip.appendChild(remove);
    detailTags.appendChild(chip);
  }
}

async function toggleFavorite(row: CaptureSummary): Promise<void> {
  const favorite = !row.favorite;
  try {
    const result = await send<MutationResult>({ type: "set-favorite", id: row.id, favorite });
    if (!result.updated) return;
    row.favorite = favorite;
    if (currentDetail?.id === row.id) {
      currentDetail.favorite = favorite;
      renderDetail(currentDetail);
    }
    await refreshStatus();
    if (!archiveEl.hidden) await refreshArchive();
  } catch {
    // Keep the previous state if the write fails.
  }
}

async function removeTag(tag: string): Promise<void> {
  if (!currentDetail) return;
  try {
    const tags = await send<string[]>({
      type: "remove-tag",
      id: currentDetail.id,
      tag,
    });
    currentDetail.tags = tags;
    renderDetailTags(tags);
    await refresh();
  } catch {
    // Keep the previous tags if the write fails.
  }
}

/* ------------------------------------------------------------------ */
/* selection                                                           */
/* ------------------------------------------------------------------ */

function setSelectionMode(enabled: boolean): void {
  selectionMode = enabled;
  if (!enabled) selectedIds.clear();
  selectToggle.setAttribute("aria-pressed", String(enabled));
  selectToggle.title = enabled ? "Cancel selection" : "Select captures";
  selectToggle.setAttribute("aria-label", selectToggle.title);
  resultsEl.classList.toggle("selecting", enabled);
  footer.hidden = enabled;
  selectionBar.hidden = !enabled;
  if (currentRows.length > 0) renderResults(currentRows, lastQuery);
  else updateSelectionBar();
}

function toggleSelection(id: string, item?: HTMLLIElement): void {
  if (selectedIds.has(id)) selectedIds.delete(id);
  else selectedIds.add(id);
  if (item) {
    item.classList.toggle("selected", selectionMode && selectedIds.has(id));
    updateSelectionBar();
  } else {
    renderResults(currentRows, lastQuery);
  }
}

function updateSelectionBar(): void {
  const count = selectedIds.size;
  selectionCount.textContent = `${count} selected`;
  selectionArchive.disabled = count === 0;
}

async function archiveSelected(): Promise<void> {
  if (selectedIds.size === 0) return;
  selectionArchive.disabled = true;
  try {
    await send<ArchiveResult>({ type: "archive-many", ids: [...selectedIds] });
    setSelectionMode(false);
    await refreshStatus();
  } catch {
    selectionArchive.disabled = false;
  }
}

/* ------------------------------------------------------------------ */
/* archive                                                             */
/* ------------------------------------------------------------------ */

async function refreshArchive(): Promise<void> {
  try {
    const rows = await send<CaptureSummary[]>({ type: "archived" });
    renderArchive(rows);
  } catch {
    renderArchive([]);
  }
}

function renderArchive(rows: CaptureSummary[]): void {
  archiveResults.replaceChildren();
  archiveEmpty.hidden = rows.length > 0;
  for (const row of rows) {
    const item = document.createElement("li");
    item.className = "archive-item";

    const main = document.createElement("button");
    main.type = "button";
    main.className = "archive-main";
    const title = document.createElement("div");
    title.className = "archive-title";
    title.textContent = row.title || row.snippet || "(untitled)";
    main.appendChild(title);
    const meta = document.createElement("div");
    meta.className = "archive-meta";
    meta.textContent = `${relative(row.created_at)} · ${row.type}`;
    main.appendChild(meta);
    main.addEventListener("click", () => void openResult(row));
    item.appendChild(main);

    const restore = document.createElement("button");
    restore.type = "button";
    restore.className = "btn btn-ghost btn-sm archive-restore";
    restore.appendChild(svgIcon("i-restore", "icon icon-sm"));
    restore.append(" Restore");
    restore.addEventListener("click", () => void restoreCapture(row.id, restore));
    item.appendChild(restore);
    archiveResults.appendChild(item);
  }
}

async function restoreCapture(id: string, button: HTMLButtonElement): Promise<void> {
  button.disabled = true;
  try {
    await send<ArchiveResult>({ type: "restore-many", ids: [id] });
    if (currentDetail?.id === id) {
      currentDetail.archived = false;
      renderDetail(currentDetail);
    }
    await refreshArchive();
    await refreshStatus();
  } catch {
    button.disabled = false;
  }
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
  const pin = $<HTMLInputElement>("setup-pin").value.trim();
  const pinConfirm = $<HTMLInputElement>("setup-pin-confirm").value.trim();
  if (!/^\d{4}$/.test(pin)) {
    errorEl.textContent = messageFor("invalid-pin");
    errorEl.hidden = false;
    return;
  }
  if (pin !== pinConfirm) {
    errorEl.textContent = "The two PINs do not match.";
    errorEl.hidden = false;
    return;
  }

  submit.disabled = true;
  setButtonLabel(submit, "Deriving key…");
  try {
    const next = await send<StatusData>({ type: "setup", passphrase, pin });
    $<HTMLInputElement>("setup-passphrase").value = "";
    $<HTMLInputElement>("setup-pin").value = "";
    $<HTMLInputElement>("setup-pin-confirm").value = "";
    applyStatus(next);
  } catch (error) {
    errorEl.textContent = messageFor(error instanceof Error ? error.message : "request-failed");
    errorEl.hidden = false;
  } finally {
    submit.disabled = false;
    setButtonLabel(submit, "Create encrypted store");
  }
});

async function handleUnlock(mode: "pin" | "passphrase"): Promise<void> {
  const submit = mode === "pin" ? unlockPinSubmit : unlockPassphraseSubmit;
  const label = mode === "pin" ? "Unlock" : "Unlock with passphrase";
  unlockError.hidden = true;

  const pin = unlockPin.value.trim();
  const passphrase = unlockPassphrase.value;
  if (mode === "pin" && !/^\d{4}$/.test(pin)) {
    unlockError.textContent = messageFor("invalid-pin");
    unlockError.hidden = false;
    unlockPin.focus();
    return;
  }
  if (mode === "passphrase" && passphrase.length === 0) {
    unlockError.textContent = "Enter your passphrase.";
    unlockError.hidden = false;
    unlockPassphrase.focus();
    return;
  }

  submit.disabled = true;
  setButtonLabel(submit, "Deriving key…");
  try {
    const next =
      mode === "pin"
        ? await send<StatusData>({ type: "unlock-pin", pin })
        : await send<StatusData>({ type: "unlock", passphrase });
    unlockPin.value = "";
    unlockPassphrase.value = "";
    applyStatus(next);
  } catch (error) {
    const code = error instanceof Error ? error.message : "request-failed";
    if (mode === "pin" && code === "pin-locked") {
      await refreshStatus().catch(() => undefined);
    }
    unlockError.textContent = messageFor(code);
    unlockError.hidden = false;
    if (mode === "pin") {
      unlockPin.value = "";
      unlockPin.focus();
    } else {
      unlockPassphrase.focus();
    }
  } finally {
    submit.disabled = false;
    setButtonLabel(submit, label);
  }
}

unlockPinForm.addEventListener("submit", (event) => {
  event.preventDefault();
  void handleUnlock("pin");
});
unlockPassphraseForm.addEventListener("submit", (event) => {
  event.preventDefault();
  void handleUnlock("passphrase");
});
unlockUsePassphrase.addEventListener("click", () => setUnlockMode("passphrase"));
unlockUsePin.addEventListener("click", () => setUnlockMode("pin"));

for (const input of document.querySelectorAll<HTMLInputElement>('input[inputmode="numeric"]')) {
  input.addEventListener("input", () => {
    const digits = input.value.replace(/\D/g, "").slice(0, 4);
    if (input.value !== digits) input.value = digits;
  });
}

/* ------------------------------------------------------------------ */
/* main controls                                                       */
/* ------------------------------------------------------------------ */

searchInput.addEventListener("input", () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => void refresh(), 140);
});

typeFilter.addEventListener("change", () => void refresh());
domainFilter.addEventListener("input", () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => void refresh(), 200);
});

favoriteFilter.addEventListener("click", () => {
  favoritesOnly = !favoritesOnly;
  favoriteFilter.setAttribute("aria-pressed", String(favoritesOnly));
  void refresh();
});

sortToggle.addEventListener("click", () => {
  descending = !descending;
  sortToggle.setAttribute("aria-pressed", String(descending));
  sortLabel.textContent = descending ? "Newest" : "Oldest";
  void refresh();
});

viewToggle.addEventListener("click", () => {
  view = view === "grid" ? "timeline" : "grid";
  viewToggle.setAttribute("aria-pressed", String(view === "timeline"));
  viewToggle.title = view === "timeline" ? "Show grid" : "Show timeline";
  viewToggle.setAttribute("aria-label", viewToggle.title);
  renderResults(currentRows, lastQuery);
});

selectToggle.addEventListener("click", () => setSelectionMode(!selectionMode));
selectionCancel.addEventListener("click", () => setSelectionMode(false));
selectionArchive.addEventListener("click", () => void archiveSelected());

$("new-capture").addEventListener("click", () => openComposer());
$("save-page").addEventListener("click", async () => {
  const button = $<HTMLButtonElement>("save-page");
  button.disabled = true;
  try {
    await send<StatusData>({ type: "save-page" });
    await refreshStatus();
  } catch {
    // Leave the current view in place; the service worker reports its own failures.
  } finally {
    button.disabled = false;
  }
});

$("sync-now").addEventListener("click", async () => {
  const button = $<HTMLButtonElement>("sync-now");
  button.disabled = true;
  syncInfo.textContent = "syncing…";
  try {
    await send({ type: "sync" });
    await refreshStatus();
  } catch {
    syncInfo.textContent = "sync failed";
  } finally {
    button.disabled = false;
  }
});

$("lock").addEventListener("click", async () => {
  try {
    const next = await send<StatusData>({ type: "lock" });
    window.clearInterval(refreshTimer);
    refreshTimer = undefined;
    applyStatus(next);
  } catch {
    // Keep the unlocked view if the service worker is unavailable.
  }
});

/* ------------------------------------------------------------------ */
/* composer                                                           */
/* ------------------------------------------------------------------ */

function openComposer(): void {
  composerForm.reset();
  composerError.hidden = true;
  composerSubmit.disabled = false;
  setButtonLabel(composerSubmit, "Save capture");
  composerEl.hidden = false;
  composerText.focus();
}

function closeComposer(): void {
  composerEl.hidden = true;
}

composerForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = composerText.value.trim();
  if (!text) {
    composerError.textContent = messageFor("empty-capture");
    composerError.hidden = false;
    composerText.focus();
    return;
  }
  composerError.hidden = true;
  composerSubmit.disabled = true;
  setButtonLabel(composerSubmit, "Saving…");
  try {
    await send<NewCaptureResult>({
      type: "new-capture",
      text,
      tags: parseTags(composerTags.value),
      favorite: composerFavorite.checked,
    });
    closeComposer();
    await refreshStatus();
  } catch (error) {
    composerError.textContent = messageFor(error instanceof Error ? error.message : "request-failed");
    composerError.hidden = false;
  } finally {
    composerSubmit.disabled = false;
    setButtonLabel(composerSubmit, "Save capture");
  }
});

$("composer-close").addEventListener("click", closeComposer);
composerEl.addEventListener("click", (event) => {
  if (event.target === composerEl) closeComposer();
});

/* ------------------------------------------------------------------ */
/* detail                                                              */
/* ------------------------------------------------------------------ */

$("detail-close").addEventListener("click", () => {
  detailEl.hidden = true;
});

detailEl.addEventListener("click", (event) => {
  if (event.target === detailEl) detailEl.hidden = true;
});

detailOpen.addEventListener("click", async () => {
  if (!currentDetail?.url) return;
  await chrome.tabs.create({ url: currentDetail.url });
  window.close();
});

detailFavorite.addEventListener("click", async () => {
  if (!currentDetail) return;
  const favorite = !currentDetail.favorite;
  try {
    const result = await send<MutationResult>({
      type: "set-favorite",
      id: currentDetail.id,
      favorite,
    });
    if (!result.updated) return;
    currentDetail.favorite = favorite;
    renderDetail(currentDetail);
    await refreshStatus();
  } catch {
    // Keep the previous state if the write fails.
  }
});

detailArchive.addEventListener("click", async () => {
  if (!currentDetail) return;
  const archived = !currentDetail.archived;
  try {
    const result = await send<MutationResult>({
      type: "set-archived",
      id: currentDetail.id,
      archived,
    });
    if (!result.updated) return;
    currentDetail.archived = archived;
    if (archived) detailEl.hidden = true;
    else renderDetail(currentDetail);
    await refreshStatus();
    if (!archiveEl.hidden) await refreshArchive();
  } catch {
    // Keep the previous state if the write fails.
  }
});

detailTagForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!currentDetail) return;
  const tag = detailTagInput.value.trim();
  if (!tag) return;
  try {
    const tags = await send<string[]>({
      type: "add-tag",
      id: currentDetail.id,
      tag,
    });
    currentDetail.tags = tags;
    renderDetailTags(tags);
    detailTagInput.value = "";
    await refresh();
  } catch {
    // Keep the previous tags if the write fails.
  }
});

/* ------------------------------------------------------------------ */
/* archive overlay                                                     */
/* ------------------------------------------------------------------ */

archiveOpen.addEventListener("click", () => {
  archiveEl.hidden = false;
  void refreshArchive();
});
$("archive-close").addEventListener("click", () => {
  archiveEl.hidden = true;
});
archiveEl.addEventListener("click", (event) => {
  if (event.target === archiveEl) archiveEl.hidden = true;
});

/* ------------------------------------------------------------------ */
/* security / PIN overlay                                              */
/* ------------------------------------------------------------------ */

function setPinError(message: string | null): void {
  pinError.textContent = message ?? "";
  pinError.hidden = message === null;
}

function openSecurity(): void {
  if (!status || status.locked || !status.configured) return;
  closeOverlays();
  securityEl.hidden = false;
  pinForm.reset();
  setPinError(null);
  const hasPin = status.pinConfigured;
  securityHint.textContent = hasPin
    ? "A 4-digit PIN is set for this browser. Enter a new PIN to replace it, or remove it and use the passphrase only."
    : "Set a 4-digit PIN to unlock this browser quickly. Your passphrase still protects encrypted sync.";
  pinFormLabel.textContent = hasPin ? "New 4-digit PIN" : "4-digit PIN";
  pinSave.textContent = hasPin ? "Change PIN" : "Set PIN";
  pinRemove.hidden = !hasPin;
  pinNew.focus();
}

function closeSecurity(): void {
  securityEl.hidden = true;
}

securityOpen.addEventListener("click", openSecurity);
pinBannerOpen.addEventListener("click", openSecurity);
$("security-close").addEventListener("click", closeSecurity);
securityEl.addEventListener("click", (event) => {
  if (event.target === securityEl) closeSecurity();
});

pinForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  setPinError(null);
  const pin = pinNew.value.trim();
  const confirm = pinConfirm.value.trim();
  if (!/^\d{4}$/.test(pin)) {
    setPinError(messageFor("invalid-pin"));
    pinNew.focus();
    return;
  }
  if (pin !== confirm) {
    setPinError("The two PINs do not match.");
    pinConfirm.focus();
    return;
  }

  pinSave.disabled = true;
  pinSave.textContent = "Saving…";
  try {
    await send<StatusData>({ type: "set-pin", pin });
    closeSecurity();
    await refreshStatus();
  } catch (error) {
    setPinError(messageFor(error instanceof Error ? error.message : "request-failed"));
  } finally {
    pinSave.disabled = false;
    pinSave.textContent = status?.pinConfigured ? "Change PIN" : "Set PIN";
  }
});

pinRemove.addEventListener("click", async () => {
  if (!status?.pinConfigured) return;
  if (!window.confirm("Remove the 4-digit PIN? You will need your passphrase to unlock.")) return;
  pinRemove.disabled = true;
  setPinError(null);
  try {
    await send<StatusData>({ type: "remove-pin" });
    closeSecurity();
    await refreshStatus();
  } catch (error) {
    setPinError(messageFor(error instanceof Error ? error.message : "request-failed"));
  } finally {
    pinRemove.disabled = false;
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  if (!securityEl.hidden) {
    closeSecurity();
  } else if (!composerEl.hidden) {
    closeComposer();
  } else if (!detailEl.hidden) {
    detailEl.hidden = true;
  } else if (!archiveEl.hidden) {
    archiveEl.hidden = true;
  } else if (selectionMode) {
    setSelectionMode(false);
  }
});

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

function parseTags(value: string): string[] {
  return value
    .split(/[,;\n]+/)
    .map((tag) => tag.trim())
    .filter(Boolean);
}

function svgIcon(name: string, className = "icon"): SVGSVGElement {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", className);
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", `#${name}`);
  svg.appendChild(use);
  return svg;
}

function setButtonIcon(button: HTMLButtonElement, name: string): void {
  const use = button.querySelector("use");
  if (use) use.setAttribute("href", `#${name}`);
}

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

function dateLabel(timestamp: number): string {
  const day = 86_400_000;
  const date = new Date(timestamp);
  const today = new Date();
  const startToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
  const startDate = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const diff = Math.round((startToday - startDate) / day);
  if (diff === 0) return "Today";
  if (diff === 1) return "Yesterday";
  if (diff < 7) return date.toLocaleDateString(undefined, { weekday: "long" });
  if (date.getFullYear() === today.getFullYear()) {
    return date.toLocaleDateString(undefined, { month: "long", day: "numeric" });
  }
  return date.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
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
    const next = await send<StatusData>({ type: "open" });
    applyStatus(next);
  } catch {
    showPanel(unlockPanel);
  }
})();
