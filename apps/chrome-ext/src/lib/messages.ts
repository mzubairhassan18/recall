import type { CaptureType, SearchResult } from "@recall/shared-types";

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

export type PopupRequest =
  | { type: "status" }
  | { type: "open" }
  | { type: "setup"; passphrase: string }
  | { type: "unlock"; passphrase: string }
  | { type: "lock" }
  | { type: "search"; query: string }
  | { type: "timeline"; captureType?: CaptureType; domain?: string }
  | { type: "capture"; id: string }
  | { type: "save-page" }
  | { type: "sync" };

export type PopupResponse =
  | { ok: true; data?: StatusData | SearchResult[] | CaptureDetail | { pulled: number; uploaded: number; failed: number } }
  | { ok: false; error: string };
