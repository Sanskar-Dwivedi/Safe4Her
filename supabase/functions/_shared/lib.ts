// Shared helpers for Security4Her Edge Functions. Dependency-free (fetch + WebCrypto only),
// so handlers run on Supabase (Deno) and in local Node tests alike.

export type Env = Record<string, string | undefined>;
export type Fetch = typeof fetch;

export class RpcError extends Error {
  readonly status: number;
  readonly code?: string;
  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export function redirect(location: string): Response {
  return new Response(null, { status: 302, headers: { location } });
}

export function required(env: Env, key: string): string {
  const v = env[key];
  if (!v) throw new Error(`missing env ${key}`);
  return v;
}

/** Calls a PostgREST RPC with the service-role key (bypasses RLS; functions do their own checks). */
export async function rpc<T>(env: Env, f: Fetch, name: string, args: Record<string, unknown>): Promise<T> {
  const url = `${required(env, "SUPABASE_URL")}/rest/v1/rpc/${name}`;
  const key = required(env, "SUPABASE_SERVICE_ROLE_KEY");
  const headers: Record<string, string> = { apikey: key, "content-type": "application/json" };
  // Legacy service-role keys are JWTs and go in Authorization too; new sb_secret_ keys go in apikey only.
  if (!key.startsWith("sb_")) headers.authorization = `Bearer ${key}`;
  const res = await f(url, { method: "POST", headers, body: JSON.stringify(args) });
  const text = await res.text();
  if (!res.ok) {
    let msg = text;
    let code: string | undefined;
    try {
      const e = JSON.parse(text);
      msg = e.message ?? text;
      code = e.code;
    } catch { /* not JSON */ }
    throw new RpcError(msg, res.status, code);
  }
  return (text ? JSON.parse(text) : null) as T;
}

export async function telegramSend(env: Env, f: Fetch, chatId: number | string, text: string): Promise<boolean> {
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return false;
  try {
    const res = await f(`https://api.telegram.org/bot${token}/sendMessage`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chat_id: chatId, text, disable_web_page_preview: false }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

export function shareUrl(env: Env, token: string | null | undefined): string | null {
  if (!token) return null;
  const base = env.TRACK_BASE_URL ?? "";
  return `${base}${base.includes("?") ? "&" : "?"}k=${encodeURIComponent(token)}`;
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// ---- small crypto helpers (WebCrypto) ----
const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64url(s: string): Uint8Array<ArrayBuffer> {
  const pad = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(pad);
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function sha256Hex(s: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s)));
  return Array.from(d, (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256B64url(s: string): Promise<string> {
  return b64url(new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s))));
}

async function aesKey(secret: string): Promise<CryptoKey> {
  const raw = await crypto.subtle.digest("SHA-256", enc.encode(secret));
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** Encrypts a small JSON payload into a URL-safe opaque string (used for the OAuth `state`). */
export async function seal(secret: string, payload: unknown): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(secret),
    enc.encode(JSON.stringify(payload))));
  return `${b64url(iv)}.${b64url(ct)}`;
}

export async function unseal<T>(secret: string, sealed: string): Promise<T | null> {
  try {
    const [iv, ct] = sealed.split(".");
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64url(iv) }, await aesKey(secret), unb64url(ct));
    return JSON.parse(dec.decode(pt)) as T;
  } catch {
    return null;
  }
}

export function randomToken(bytes = 32): string {
  return b64url(crypto.getRandomValues(new Uint8Array(bytes)));
}
