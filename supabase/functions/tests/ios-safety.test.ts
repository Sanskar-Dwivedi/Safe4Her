import { test } from "node:test";
import assert from "node:assert/strict";
import { handler, alertText } from "../ios-safety/handler.ts";

const env = { SUPABASE_URL: "https://example.supabase.co", SUPABASE_ANON_KEY: "anon",
  SUPABASE_SERVICE_ROLE_KEY: "service", TELEGRAM_BOT_TOKEN: "test-bot", IOS_DISPATCH_SECRET: "dispatch",
  TRACK_BASE_URL: "https://example.com/track/" };
const post = (body: unknown, headers = { authorization: "Bearer user-token" }) =>
  new Request("https://example.com/ios-safety", { method: "POST", headers, body: JSON.stringify(body) });
type Call = { url: string; body: Record<string, any>; init?: RequestInit };
function mock(state: Record<string, unknown> = {}, telegramStatus = 200) {
  const calls: Call[] = [];
  const fetcher = (async (input, init) => {
    const url = String(input), body = init?.body ? JSON.parse(String(init.body)) : {};
    calls.push({ url, body, init });
    if (url.endsWith("/auth/v1/user")) return Response.json({ id: "owner" });
    if (url.endsWith("/ios_safety")) return Response.json(state);
    if (url.endsWith("/ios_claim_alerts")) return Response.json([{ id: "job", lease: "lease-1", event: "sos", chatId: 42, name: "Asha", trigger: "journey", token: "share" }]);
    if (url.includes("api.telegram.org")) return Response.json({ ok: telegramStatus === 200, parameters: { retry_after: 120 } }, { status: telegramStatus });
    if (url.endsWith("/ios_finish_delivery") || url.endsWith("/ios_worker_heartbeat")) return Response.json(null);
    throw new Error(`Unexpected mock request: ${url}`);
  }) as typeof fetch;
  return { calls, fetcher };
}

test("iOS rejects missing auth, wrong dispatch secret, methods and unknown actions", async () => {
  const { calls, fetcher } = mock();
  assert.equal((await handler(post({}, {} as any), env, fetcher)).status, 401);
  assert.equal((await handler(post({}, { "x-s4h-dispatch-secret": "wrong" } as any), env, fetcher)).status, 401);
  assert.equal((await handler(new Request("https://example.com"), env, fetcher)).status, 405);
  assert.equal((await handler(post({ action: "unknown" }), env, fetcher)).status, 400);
  assert.equal(calls.filter(c => !c.url.endsWith("/auth/v1/user")).length, 0);
});
test("capabilities report actual delivery configuration and eligible contacts", async () => {
  const { fetcher } = mock({ schedulerReady: true, eligibleRecipients: 2 });
  const result = await (await handler(post({ action: "capabilities" }), env, fetcher)).json();
  assert.equal(result.deliveryReady, true); assert.equal(result.eligibleRecipients, 2);
  const missing = await (await handler(post({ action: "capabilities" }), { ...env, TRACK_BASE_URL: "" }, fetcher)).json();
  assert.equal(missing.deliveryReady, false);
  assert.equal((await handler(post({ action: "journeyStart" }), { ...env, TELEGRAM_BOT_TOKEN: "" }, fetcher)).status, 503);
});
test("journey response retains authoritative deadline, revision and incident status", async () => {
  const { calls, fetcher } = mock({ state: "alerted", incidentState: "resolved", deadline: "2026-10-03T12:00:00Z", revision: 4 });
  const result = await (await handler(post({ action: "journeyExtend", journeyId: "journey", revision: 4 }), env, fetcher)).json();
  assert.equal(result.incidentState, "resolved"); assert.equal(result.revision, 4);
  assert.equal(result.deadline, "2026-10-03T12:00:00Z");
  const rpc = calls.find(c => c.url.endsWith("/ios_safety"))!;
  assert.equal(rpc.body.p_data.revision, 4);
  assert.equal((rpc.init?.headers as any).authorization, "Bearer user-token");
});
test("test SOS with no queued jobs does not contact Telegram", async () => {
  const { calls, fetcher } = mock({ state: "active", pending: 0 });
  await handler(post({ action: "sos", test: true }), env, fetcher);
  assert.equal(calls.filter(c => c.url.includes("telegram.org") || c.url.endsWith("/ios_claim_alerts")).length, 0);
});
test("delivery acknowledges the claimed lease; rate limits remain pending", async () => {
  const { calls, fetcher } = mock({}, 429);
  const response = await handler(post({}, { "x-s4h-dispatch-secret": "dispatch" } as any), env, fetcher);
  assert.equal(response.status, 200);
  assert.deepEqual(calls.find(c => c.url.endsWith("/ios_finish_delivery"))?.body,
    { p_id: "job", p_lease: "lease-1", p_sent: false, p_retry_seconds: 120 });
  assert.ok(calls.every(c => c.init?.signal));
});
test("Telegram acceptance and lifecycle text distinguish stopping from arrival", async () => {
  const { calls, fetcher } = mock({ state: "active", pending: 1 });
  await handler(post({ action: "sos", test: false }), env, fetcher);
  assert.equal(calls.find(c => c.url.endsWith("/ios_finish_delivery"))?.body.p_sent, true);
  const job = { id: "job", lease: "lease", chatId: 42, name: "Asha", event: "journey_ended" };
  assert.match(alertText(job, env), /does not confirm arrival/);
  assert.match(alertText({ ...job, event: "journey_arrived" }, env), /checked in/);
  assert.match(alertText({ ...job, event: "resolved" }, env), /ended their SOS/);
  assert.doesNotMatch(alertText({ ...job, event: "sos", token: "secret" }, { ...env, TRACK_BASE_URL: "" }), /\?k=/);
});
