import type {
  CaptureDetail,
  MutationResult,
  PopupRequest,
  PopupResponse,
  StatusData,
} from "../lib/messages.js";

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const captureId = new URLSearchParams(location.search).get("id") ?? "";

const loadingEl = $("editor-loading");
const lockedEl = $("editor-locked");
const lockedCopy = $("editor-locked-copy");
const retryButton = $<HTMLButtonElement>("editor-retry");
const form = $<HTMLFormElement>("editor-form");

const metaEl = $("editor-meta");
const titlePreview = $("editor-title-preview");
const archivedPill = $("editor-archived-pill");
const image = $<HTMLImageElement>("editor-image");

const titleInput = $<HTMLInputElement>("editor-title");
const textInput = $<HTMLTextAreaElement>("editor-text");
const urlInput = $<HTMLInputElement>("editor-url");
const ocrField = $("editor-ocr-field");
const ocrInput = $<HTMLTextAreaElement>("editor-ocr");

const tagsEl = $("editor-tags");
const tagCount = $("editor-tag-count");
const tagInput = $<HTMLInputElement>("editor-tag-input");
const tagAdd = $<HTMLButtonElement>("editor-tag-add");

const errorEl = $("editor-error");
const noticeEl = $("editor-notice");
const saveButton = $<HTMLButtonElement>("editor-save");
const openButton = $<HTMLButtonElement>("editor-open");
const favoriteButton = $<HTMLButtonElement>("editor-favorite");
const archiveButton = $<HTMLButtonElement>("editor-archive");
const doneButton = $<HTMLButtonElement>("editor-done");
const closeButton = $<HTMLButtonElement>("editor-close");
const lockedCloseButton = $<HTMLButtonElement>("editor-locked-close");

const MAX_TAGS = 8;

const ERROR_TEXT: Record<string, string> = {
  "not-found": "That capture is no longer available.",
  "locked": "Unlock Recall first.",
  "not-configured": "Set up Recall first.",
  "request-failed": "Something went wrong. Try again.",
  "invalid-url": "The link must start with http:// or https://.",
};

let detail: CaptureDetail | null = null;
let tags: string[] = [];
let dirty = false;

function messageFor(code: string): string {
  return ERROR_TEXT[code] ?? ERROR_TEXT["request-failed"]!;
}

async function send<T>(request: PopupRequest): Promise<T> {
  const response = (await chrome.runtime.sendMessage(request)) as PopupResponse;
  if (!response || !response.ok) throw new Error(response?.error ?? "request-failed");
  return response.data as T;
}

function showOnly(target: HTMLElement): void {
  for (const section of [loadingEl, lockedEl, form]) {
    section.hidden = section !== target;
  }
}

function setError(message: string | null): void {
  errorEl.textContent = message ?? "";
  errorEl.hidden = message === null;
}

function setNotice(message: string | null): void {
  noticeEl.textContent = message ?? "";
  noticeEl.hidden = message === null;
}

function setDirty(): void {
  dirty = true;
  setNotice(null);
}

function normalizeTag(value: string): string {
  return value.trim().toLowerCase().slice(0, 32);
}

function previewTitle(): string {
  const title = titleInput.value.trim();
  if (title) return title;
  const text = textInput.value.trim();
  if (text) return text.split("\n")[0]!.slice(0, 90);
  const ocr = ocrInput.value.trim();
  if (ocr) return ocr.split("\n")[0]!.slice(0, 90);
  return detail?.url ?? "(untitled)";
}

function renderMeta(): void {
  if (!detail) return;
  const archived = detail.archived ? " · archived" : "";
  metaEl.textContent = `${detail.type} · ${new Date(detail.created_at).toLocaleString()}${archived}`;
  titlePreview.textContent = previewTitle();
  archivedPill.hidden = !detail.archived;

  favoriteButton.setAttribute("aria-pressed", String(detail.favorite));
  const favoriteLabel = favoriteButton.querySelector("span");
  if (favoriteLabel) favoriteLabel.textContent = detail.favorite ? "Favorited" : "Favorite";

  const archiveLabel = archiveButton.querySelector("span");
  if (archiveLabel) archiveLabel.textContent = detail.archived ? "Restore" : "Archive";
  setButtonIcon(archiveButton, detail.archived ? "i-restore" : "i-archive");

  openButton.hidden = !detail.url;
  if (detail.thumbnail_b64) {
    image.src = `data:image/png;base64,${detail.thumbnail_b64}`;
    image.hidden = false;
  } else {
    image.hidden = true;
    image.removeAttribute("src");
  }
}

function renderTags(): void {
  tagsEl.replaceChildren();
  tagCount.textContent = `${tags.length}/${MAX_TAGS}`;
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
    remove.addEventListener("click", () => removeTag(tag));
    chip.appendChild(remove);
    tagsEl.appendChild(chip);
  }
}

function fillFields(next: CaptureDetail): void {
  titleInput.value = next.title;
  textInput.value = next.text;
  urlInput.value = next.url ?? "";
  ocrInput.value = next.ocr_text;
  ocrField.hidden = next.ocr_text.trim().length === 0;
  renderMeta();
}

function setButtonIcon(button: HTMLButtonElement, symbol: string): void {
  const use = button.querySelector("use");
  if (use) use.setAttribute("href", `#${symbol}`);
}

function addTag(): void {
  const tag = normalizeTag(tagInput.value);
  if (!tag) return;
  if (tags.length >= MAX_TAGS) {
    setError(`A capture can have up to ${MAX_TAGS} tags.`);
    return;
  }
  if (tags.some((value) => value.toLowerCase() === tag)) {
    tagInput.value = "";
    return;
  }
  tags = [...tags, tag];
  tagInput.value = "";
  setError(null);
  renderTags();
  setDirty();
}

function removeTag(tag: string): void {
  tags = tags.filter((value) => value.toLowerCase() !== tag.toLowerCase());
  renderTags();
  setDirty();
}

async function save(): Promise<void> {
  if (!detail || saveButton.disabled) return;
  const title = titleInput.value.trim();
  const text = textInput.value.trim();
  const ocrText = ocrInput.value.trim();
  const url = urlInput.value.trim();
  if (!title && !text && !ocrText && !url) {
    setError("Keep a title, some text, or a link.");
    return;
  }
  if (url && !/^https?:\/\//i.test(url)) {
    setError(messageFor("invalid-url"));
    return;
  }

  setError(null);
  setNotice(null);
  saveButton.disabled = true;
  saveButton.textContent = "Saving…";
  try {
    const updated = await send<CaptureDetail>({
      type: "update-capture",
      id: detail.id,
      title,
      text,
      ocrText,
      url: url || null,
      tags,
    });
    detail = updated;
    tags = [...updated.tags];
    fillFields(updated);
    renderTags();
    dirty = false;
    setNotice("Saved. Encrypted and queued for sync.");
  } catch (error) {
    setError(messageFor(error instanceof Error ? error.message : "request-failed"));
  } finally {
    saveButton.disabled = false;
    saveButton.textContent = "Save changes";
  }
}

async function toggleFavorite(): Promise<void> {
  if (!detail) return;
  const favorite = !detail.favorite;
  try {
    const result = await send<MutationResult>({
      type: "set-favorite",
      id: detail.id,
      favorite,
    });
    if (!result.updated) return;
    detail.favorite = favorite;
    renderMeta();
    setNotice(favorite ? "Added to favorites." : "Removed from favorites.");
  } catch (error) {
    setError(messageFor(error instanceof Error ? error.message : "request-failed"));
  }
}

async function toggleArchived(): Promise<void> {
  if (!detail) return;
  const archived = !detail.archived;
  try {
    const result = await send<MutationResult>({
      type: "set-archived",
      id: detail.id,
      archived,
    });
    if (!result.updated) return;
    detail.archived = archived;
    renderMeta();
    setNotice(archived ? "Moved to archive." : "Restored from archive.");
  } catch (error) {
    setError(messageFor(error instanceof Error ? error.message : "request-failed"));
  }
}

function closeEditor(): void {
  if (dirty && !window.confirm("Discard unsaved changes?")) return;
  window.close();
}

function showLocked(copy: string, retry: boolean): void {
  lockedCopy.textContent = copy;
  retryButton.hidden = !retry;
  showOnly(lockedEl);
}

async function load(): Promise<void> {
  showOnly(loadingEl);
  setError(null);
  setNotice(null);
  if (!captureId) {
    showLocked("This editor was opened without a capture id.", false);
    return;
  }

  try {
    const status = await send<StatusData>({ type: "status" });
    if (!status.configured) {
      showLocked("Set up Recall from the toolbar first.", false);
      return;
    }
    if (status.locked) {
      showLocked("Unlock Recall from the toolbar, then try again.", true);
      return;
    }

    const next = await send<CaptureDetail>({ type: "capture", id: captureId });
    detail = next;
    tags = [...next.tags];
    dirty = false;
    fillFields(next);
    renderTags();
    showOnly(form);
    titleInput.focus();
  } catch (error) {
    showLocked(messageFor(error instanceof Error ? error.message : "request-failed"), true);
  }
}

/* ------------------------------------------------------------------ */
/* events                                                              */
/* ------------------------------------------------------------------ */

for (const input of [titleInput, textInput, urlInput, ocrInput]) {
  input.addEventListener("input", () => {
    setDirty();
    renderMeta();
  });
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  void save();
});

tagAdd.addEventListener("click", addTag);
tagInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    addTag();
  }
});

favoriteButton.addEventListener("click", () => void toggleFavorite());
archiveButton.addEventListener("click", () => void toggleArchived());

openButton.addEventListener("click", () => {
  if (detail?.url) void chrome.tabs.create({ url: detail.url });
});

doneButton.addEventListener("click", closeEditor);
closeButton.addEventListener("click", closeEditor);
lockedCloseButton.addEventListener("click", closeEditor);
retryButton.addEventListener("click", () => void load());

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeEditor();
});

window.addEventListener("beforeunload", (event) => {
  if (!dirty) return;
  event.preventDefault();
  event.returnValue = "";
});

void load();
