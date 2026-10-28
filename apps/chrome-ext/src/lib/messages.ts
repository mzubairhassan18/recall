import type { CaptureType } from "@recall/shared-types";
import type { CaptureSummary } from "./db.js";

export interface StatusData {
  configured: boolean;
  locked: boolean;
  pinConfigured: boolean;
  pinLockedUntil: number | null;
  localOnly: boolean;
  relayUrl: string | null;
  count: number;
  archivedCount: number;
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
  favorite: boolean;
  archived: boolean;
  tags: string[];
  thumbnail_b64?: string;
}

export interface CaptureQuery {
  captureType?: CaptureType;
  domain?: string;
  favoritesOnly?: boolean;
  tag?: string;
  descending?: boolean;
}

export interface ArchiveResult {
  count: number;
}

export interface MutationResult {
  updated: boolean;
}

export interface NewCaptureResult {
  id: string;
}

export interface OpenEditorResult {
  windowId: number | null;
}

export type PopupRequest =
  | { type: "status" }
  | { type: "open" }
  | { type: "setup"; passphrase: string; pin: string }
  | { type: "unlock"; passphrase: string }
  | { type: "unlock-pin"; pin: string }
  | { type: "set-pin"; pin: string }
  | { type: "remove-pin" }
  | { type: "lock" }
  | ({ type: "search"; query: string } & CaptureQuery)
  | ({ type: "timeline" } & CaptureQuery)
  | { type: "archived" }
  | { type: "tags" }
  | { type: "capture"; id: string }
  | { type: "open-editor"; id: string }
  | {
      type: "update-capture";
      id: string;
      title: string;
      text: string;
      ocrText: string;
      url: string | null;
      tags: string[];
    }
  | { type: "save-page" }
  | { type: "new-capture"; text: string; tags?: string[]; favorite?: boolean }
  | { type: "set-favorite"; id: string; favorite: boolean }
  | { type: "set-archived"; id: string; archived: boolean }
  | { type: "archive-many"; ids: string[] }
  | { type: "restore-many"; ids: string[] }
  | { type: "add-tag"; id: string; tag: string }
  | { type: "remove-tag"; id: string; tag: string }
  | { type: "sync" };

export type PopupResponse =
  | {
      ok: true;
      data?:
        | StatusData
        | CaptureSummary[]
        | CaptureDetail
        | string[]
        | ArchiveResult
        | MutationResult
        | NewCaptureResult
        | OpenEditorResult
        | { pulled: number; uploaded: number; failed: number };
    }
  | { ok: false; error: string };
