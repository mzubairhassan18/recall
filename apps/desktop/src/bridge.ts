import type { SearchResult } from "@recall/shared-types";
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
import { boot, dispatch, onChanged, onFocusSearch } from "./core/app.js";

/**
 * `window.recall` for the Tauri build. Same shape as the Electron preload bridge, so the
 * renderer (`src/renderer/main.ts`) and every UI behaviour stay untouched: calls land on the
 * in-page core, which uses the Rust host only for OS operations.
 */

export interface RecallBridge {
  status(): Promise<StatusData>;
  setup(input: SetupInput): Promise<StatusData>;
  unlock(passphrase: string): Promise<StatusData>;
  lock(): Promise<StatusData>;
  search(input: SearchInput): Promise<SearchResult[]>;
  timeline(input: TimelineInput): Promise<SearchResult[]>;
  detail(id: string): Promise<CaptureDetail>;
  saveClipboard(): Promise<CaptureResult>;
  sync(): Promise<SyncResult>;
  openExternal(url: string): Promise<void>;
  quit(): Promise<void>;
  onChanged(callback: () => void): () => void;
  onFocusSearch(callback: () => void): () => void;
}

async function invoke<T>(channel: string, argument?: unknown): Promise<T> {
  const result = (await dispatch(channel, argument)) as IpcResult<T>;
  if (!result || !result.ok) throw new Error(result?.error ?? "request-failed");
  return result.data;
}

const bridge: RecallBridge = {
  status: () => invoke<StatusData>("recall:status"),
  setup: (input) => invoke<StatusData>("recall:setup", input),
  unlock: (passphrase) => invoke<StatusData>("recall:unlock", passphrase),
  lock: () => invoke<StatusData>("recall:lock"),
  search: (input) => invoke<SearchResult[]>("recall:search", input),
  timeline: (input) => invoke<SearchResult[]>("recall:timeline", input),
  detail: (id) => invoke<CaptureDetail>("recall:detail", id),
  saveClipboard: () => invoke<CaptureResult>("recall:save-clipboard"),
  sync: () => invoke<SyncResult>("recall:sync"),
  openExternal: (url) => invoke<void>("recall:open-external", url),
  quit: () => invoke<void>("recall:quit"),
  onChanged,
  onFocusSearch,
};

(globalThis as unknown as { recall: RecallBridge }).recall = bridge;

void boot().catch(() => undefined);
