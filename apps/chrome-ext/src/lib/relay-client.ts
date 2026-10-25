import type { AuthRegisterResponse, Envelope, SyncItem } from "@recall/shared-types";

export interface RelayCredentials {
  relayUrl: string;
  relaySecret: string;
  userId: string;
}

export class RelayError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string) {
    super(code);
    this.name = "RelayError";
    this.status = status;
    this.code = code;
  }
}

export interface RelayApi {
  register(username: string): Promise<AuthRegisterResponse>;
  putBlob(id: string, envelope: Envelope, createdAt: number): Promise<void>;
  getBlob(id: string): Promise<Envelope>;
  list(since: number): Promise<SyncItem[]>;
}

/** Thin fetch client for docs/api.md. Never logs headers, bodies, or secrets. */
export class RelayClient implements RelayApi {
  private readonly base: string;
  private readonly secret: string;
  private readonly userId: string;

  constructor(credentials: RelayCredentials) {
    this.base = credentials.relayUrl.replace(/\/+$/, "");
    this.secret = credentials.relaySecret;
    this.userId = credentials.userId;
  }

  async register(username: string): Promise<AuthRegisterResponse> {
    const response = await this.send("/auth/register", {
      method: "POST",
      body: JSON.stringify({ username }),
      headers: { "Content-Type": "application/json" },
    });
    return (await response.json()) as AuthRegisterResponse;
  }

  async putBlob(id: string, envelope: Envelope, createdAt: number): Promise<void> {
    await this.send(`/blob/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(envelope),
      headers: { "Content-Type": "application/json", "X-Created-At": String(createdAt) },
    });
  }

  async getBlob(id: string): Promise<Envelope> {
    const response = await this.send(`/blob/${encodeURIComponent(id)}`, { method: "GET" });
    return (await response.json()) as Envelope;
  }

  async list(since: number): Promise<SyncItem[]> {
    const response = await this.send(`/list?since=${Math.max(0, Math.floor(since))}`, {
      method: "GET",
    });
    const value = (await response.json()) as unknown;
    return Array.isArray(value) ? (value as SyncItem[]) : [];
  }

  async health(): Promise<boolean> {
    try {
      const response = await fetch(`${this.base}/health`, { signal: AbortSignal.timeout(10_000) });
      return response.ok;
    } catch {
      return false;
    }
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    let response: Response;
    try {
      response = await fetch(`${this.base}${path}`, {
        ...init,
        headers: {
          ...(init.headers as Record<string, string>),
          "X-Relay-Secret": this.secret,
          ...(this.userId ? { "X-User-Id": this.userId } : {}),
        },
        signal: AbortSignal.timeout(20_000),
      });
    } catch {
      throw new RelayError(0, "network");
    }
    if (!response.ok) {
      let code = "http-error";
      try {
        const body = (await response.json()) as { error?: unknown };
        if (typeof body.error === "string") code = body.error;
      } catch {
        // keep the generic code
      }
      throw new RelayError(response.status, code);
    }
    return response;
  }
}
