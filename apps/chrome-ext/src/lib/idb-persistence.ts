import type { SqlitePersistence } from "./db.js";

const DB_NAME = "recall";
const DB_VERSION = 1;
const OBJECT_STORE = "kv";
const BLOB_KEY = "sqlite";

/** IndexedDB-backed byte persistence. Values written here are already encrypted. */
export class IdbPersistence implements SqlitePersistence {
  private readonly db: IDBDatabase;

  private constructor(db: IDBDatabase) {
    this.db = db;
  }

  static open(): Promise<IdbPersistence> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(OBJECT_STORE)) db.createObjectStore(OBJECT_STORE);
      };
      request.onsuccess = () => resolve(new IdbPersistence(request.result));
      request.onerror = () => reject(request.error ?? new Error("indexeddb-open-failed"));
    });
  }

  load(): Promise<Uint8Array | null> {
    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(OBJECT_STORE, "readonly");
      const request = transaction.objectStore(OBJECT_STORE).get(BLOB_KEY);
      request.onsuccess = () => {
        const value = request.result as unknown;
        resolve(value instanceof Uint8Array ? value : null);
      };
      request.onerror = () => reject(request.error ?? new Error("indexeddb-read-failed"));
    });
  }

  save(bytes: Uint8Array): Promise<void> {
    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(OBJECT_STORE, "readwrite");
      transaction.objectStore(OBJECT_STORE).put(bytes, BLOB_KEY);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error ?? new Error("indexeddb-write-failed"));
      transaction.onabort = () => reject(transaction.error ?? new Error("indexeddb-write-aborted"));
    });
  }
}
