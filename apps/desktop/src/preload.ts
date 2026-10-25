import { contextBridge, ipcRenderer } from "electron";
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

async function invoke<T>(channel: string, argument?: unknown): Promise<T> {
  const result = (await ipcRenderer.invoke(channel, argument)) as IpcResult<T>;
  if (!result || !result.ok) throw new Error(result?.error ?? "request-failed");
  return result.data;
}

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
  onChanged: (callback) => {
    const listener = (): void => callback();
    ipcRenderer.on("recall:changed", listener);
    return () => ipcRenderer.removeListener("recall:changed", listener);
  },
  onFocusSearch: (callback) => {
    const listener = (): void => callback();
    ipcRenderer.on("recall:focus-search", listener);
    return () => ipcRenderer.removeListener("recall:focus-search", listener);
  },
};

contextBridge.exposeInMainWorld("recall", bridge);
