// Run: node --experimental-strip-types --test supabase/functions/tests/
import { test } from "node:test";
import assert from "node:assert/strict";
import { handler as watchSos } from "../watch-sos/handler.ts";
import { handler as telegram } from "../telegram-webhook/handler.ts";
import { handler as dlStart } from "../digilocker-start/handler.ts";
import { handler as dlCallback } from "../digilocker-callback/handler.ts";
import { extractGender } from "../_shared/digilocker.ts";
import { seal, unseal } from "../_shared/lib.ts";

const env = {
  SUPABASE_URL: "https://proj.supabase.co",
  SUPABASE_SERVICE_ROLE_KEY: "service-jwt",
  SUPABASE_ANON_KEY: "anon",
  TELEGRAM_BOT_TOKEN: "bot123",
  TELEGRAM_WEBHOOK_SECRET: "hook-secret",
  TRACK_BASE_URL: "https://x.github.io/Safe4Her/",
};

type Call = { url: string; init?: RequestInit };
function mockFetch(routes: (url: string, init?: RequestInit) => Response | undefined) {
  const calls: Call[] = [];
  const f = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return routes(url, init) ?? new Response("not mocked", { status: 599 });
  }) as typeof fetch;
  return { f, calls };
}
const ID = "11111111-2222-4333-8444-555555555555";
const post = (body: unknown, headers: Record<string, string> = {}) =>
  new Request("https://fn/watch-sos", { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });

test("watch-sos start: calls RPC with device token, sends Telegram to linked contacts once", async () => {
  const { f, calls } = mockFetch((url) => {
    if (url.endsWith("/rest/v1/rpc/watch_sos")) {
      return Response.json({ created: true, status: "active", share_token: "tok", display_name: "Asha",
        contacts: [{ name: "Mom", phone: "1", telegram_chat_id: 42 }, { name: "Priya", phone: "2", telegram_chat_id: null }] });
    }
    if (url.includes("api.telegram.org/botbot123/sendMessage")) return Response.json({ ok: true });
  });
  const res = await watchSos(post({ action: "start", incidentId: ID, trigger: "knock", lat: 28.6, lng: 77.2, accuracyM: 9.4, test: true },
    { "x-device-token": "dev" }), env, f);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body, { ok: true, created: true, status: "active", shareUrl: "https://x.github.io/Safe4Her/?k=tok", telegramSent: 1 });
  const rpcArgs = JSON.parse(String(calls[0].init!.body));
  assert.equal(rpcArgs.p_device_token, "dev");
  assert.equal(rpcArgs.p_incident_id, ID);
  assert.equal(rpcArgs.p_test, true);
  const headers = calls[0].init!.headers as Record<string, string>;
  assert.equal(headers.authorization, "Bearer service-jwt");
  const tg = JSON.parse(String(calls[1].init!.body));
  assert.equal(tg.chat_id, 42);
  assert.match(tg.text, /^\[TEST MODE\] 🚨 SOS from Asha/);
  assert.match(tg.text, /maps\.google\.com\/\?q=28\.6,77\.2 \(±9 m\)/);
  assert.match(tg.text, /\?k=tok/);
});

test("watch-sos repeat start (created=false) sends no Telegram", async () => {
  const { f, calls } = mockFetch((url) => url.endsWith("/rpc/watch_sos")
    ? Response.json({ created: false, status: "active", share_token: "tok", display_name: "Asha", contacts: [{ name: "Mom", phone: "1", telegram_chat_id: 42 }] })
    : undefined);
  const res = await watchSos(post({ action: "start", incidentId: ID }, { "x-device-token": "dev" }), env, f);
  assert.equal((await res.json()).telegramSent, 0);
  assert.equal(calls.length, 1);
});

test("watch-sos maps RPC errors to HTTP codes", async () => {
  const { f } = mockFetch(() => Response.json({ message: "invalid device token", code: "28000" }, { status: 400 }));
  const res = await watchSos(post({ action: "start", incidentId: ID }, { "x-device-token": "bad" }), env, f);
  assert.equal(res.status, 401);
});

test("watch-sos validates input before calling the DB", async () => {
  const { f, calls } = mockFetch(() => undefined);
  assert.equal((await watchSos(post({ action: "start", incidentId: ID }), env, f)).status, 401);
  assert.equal((await watchSos(post({ action: "nope", incidentId: ID }, { "x-device-token": "d" }), env, f)).status, 400);
  assert.equal((await watchSos(post({ action: "start", incidentId: "x" }, { "x-device-token": "d" }), env, f)).status, 400);
  assert.equal((await watchSos(post({ action: "location", incidentId: ID }, { "x-device-token": "d" }), env, f)).status, 400);
  assert.equal(calls.length, 0);
});

test("watch-sos with new sb_secret key sends apikey only", async () => {
  const { f, calls } = mockFetch(() => Response.json({ created: false, status: "resolved", share_token: null, display_name: "A", contacts: [] }));
  await watchSos(post({ action: "cancel", incidentId: ID }, { "x-device-token": "d" }), { ...env, SUPABASE_SERVICE_ROLE_KEY: "sb_secret_x" }, f);
  const headers = calls[0].init!.headers as Record<string, string>;
  assert.equal(headers.apikey, "sb_secret_x");
  assert.equal(headers.authorization, undefined);
});

test("telegram-webhook rejects wrong secret and links a contact with /start code", async () => {
  const { f, calls } = mockFetch((url) => {
    if (url.endsWith("/rpc/telegram_link")) return Response.json({ contact_name: "Mom", owner_name: "Asha" });
    if (url.includes("sendMessage")) return Response.json({ ok: true });
  });
  const upd = { message: { chat: { id: 77 }, text: "/start AbC12345" } };
  const bad = new Request("https://fn", { method: "POST", headers: { "x-telegram-bot-api-secret-token": "nope" }, body: JSON.stringify(upd) });
  assert.equal((await telegram(bad, env, f)).status, 401);
  const ok = new Request("https://fn", { method: "POST", headers: { "x-telegram-bot-api-secret-token": "hook-secret" }, body: JSON.stringify(upd) });
  assert.equal((await telegram(ok, env, f)).status, 200);
  assert.deepEqual(JSON.parse(String(calls[0].init!.body)), { p_code: "abc12345", p_chat_id: 77 });
  assert.match(JSON.parse(String(calls[1].init!.body)).text, /SOS alerts from Asha/);
});

test("digilocker-start returns to app when not configured", async () => {
  const { f } = mockFetch(() => undefined);
  const res = await dlStart(new Request("https://fn/digilocker-start?access_token=x"), env, f);
  assert.equal(res.status, 302);
  assert.equal(res.headers.get("location"), "security4her://verified?ok=0&reason=digilocker_not_configured");
});

const dlEnv = { ...env, DIGILOCKER_MODE: "digilocker", DIGILOCKER_CLIENT_ID: "cid", DIGILOCKER_CLIENT_SECRET: "cs",
  DIGILOCKER_REDIRECT_URI: "https://proj.supabase.co/functions/v1/digilocker-callback", DIGILOCKER_STATE_SECRET: "state-secret" };

test("digilocker start → callback happy path verifies a female user", async () => {
  const { f, calls } = mockFetch((url) => {
    if (url.endsWith("/auth/v1/user")) return Response.json({ id: "user-1" });
    if (url.includes("/oauth2/1/token")) return Response.json({ access_token: "at", digilockerid: "dl-9", gender: "F" });
    if (url.endsWith("/rpc/set_verified_from_digilocker")) return Response.json(true);
  });
  const start = await dlStart(new Request("https://fn/digilocker-start?access_token=jwt"), dlEnv, f);
  const loc = new URL(start.headers.get("location")!);
  assert.equal(loc.searchParams.get("client_id"), "cid");
  assert.equal(loc.searchParams.get("code_challenge_method"), "S256");
  const state = loc.searchParams.get("state")!;
  const cb = await dlCallback(new Request(`https://fn/digilocker-callback?code=c1&state=${encodeURIComponent(state)}`), dlEnv, f);
  assert.equal(cb.headers.get("location"), "security4her://verified?ok=1");
  const tokenBody = new URLSearchParams(String(calls[1].init!.body));
  assert.equal(tokenBody.get("code"), "c1");
  assert.ok((tokenBody.get("code_verifier") ?? "").length >= 43);
  const rpcArgs = JSON.parse(String(calls[2].init!.body));
  assert.equal(rpcArgs.p_user, "user-1");
  assert.equal(rpcArgs.p_is_female, true);
  assert.match(rpcArgs.p_sub_hash, /^[0-9a-f]{64}$/);
});

test("digilocker callback rejects tampered state and non-female users", async () => {
  const { f } = mockFetch((url) => {
    if (url.includes("/oauth2/1/token")) return Response.json({ access_token: "at", digilockerid: "dl-1", gender: "M" });
    if (url.endsWith("/rpc/set_verified_from_digilocker")) return Response.json(false);
  });
  const bad = await dlCallback(new Request("https://fn/cb?code=c&state=garbage.x"), dlEnv, f);
  assert.equal(bad.headers.get("location"), "security4her://verified?ok=0&reason=invalid_state");
  const st = await seal("state-secret", { u: "u2", v: "v".repeat(50), exp: Date.now() + 60000 });
  const male = await dlCallback(new Request(`https://fn/cb?code=c&state=${encodeURIComponent(st)}`), dlEnv, f);
  assert.equal(male.headers.get("location"), "security4her://verified?ok=0&reason=not_eligible");
});

test("helpers: gender extraction and sealed state round-trip", async () => {
  assert.equal(extractGender({}, '<Certificate><Poi name="A" gender="F" dob="01-01-2000"/></Certificate>'), "F");
  assert.equal(extractGender({ gender: "female" }), "F");
  assert.equal(extractGender({}), null);
  const s = await seal("k", { a: 1 });
  assert.deepEqual(await unseal("k", s), { a: 1 });
  assert.equal(await unseal("other", s), null);
});
