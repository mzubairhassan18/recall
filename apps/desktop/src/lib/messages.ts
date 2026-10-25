import type { CaptureType } from "@recall/shared-types";

export interface StatusData {
  configured: boolean;
  locked: boolean;
  localOnly: boolean;
  relayUrl: string | null;
  count: number;
  syncing: boolean;
  lastSyncAt: number | null;
  lastSyncError: string | null;
}

export interface CaptureDetail {
  id: string;
  title: string;
  text: string;
  ocr_text: string;
  url: string | null;
  type: CaptureType;
  created_at: number;
  thumbnail_b64?: string;
}

export interface SetupInput {
  passphrase: string;
  /** Omit to use the compiled-in Recall relay defaults. */
  relayUrl?: string;
  relaySecret?: string;
}

export interface SearchInput {
  query: string;
}

export interface TimelineInput {
  captureType?: CaptureType;
  domain?: string;
}

export interface SyncResult {
  pulled: number;
  uploaded: number;
  failed: number;
}

export interface CaptureResult {
  saved: boolean;
  kind?: CaptureType;
  id?: string;
  reason?: "empty-clipboard" | "locked";
}

export type IpcResult<T> = { ok: true; data: T } | { ok: false; error: string };
