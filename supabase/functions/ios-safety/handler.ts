import { type Env, type Fetch, json, required, rpc, shareUrl } from "../_shared/lib.ts";

interface AlertJob { id: string; lease: string; chatId: number; name: string; token?: string; trigger?: string; event: string; destination?: string; deadline?: string }
interface State { state?: string; shareToken?: string; accepted?: number; pending?: number; schedulerReady?: boolean;
  deadline?: string; revision?: number; incidentState?: string; eligibleRecipients?: number }

function trackingConfigured(env: Env): boolean {
  try { return new URL(env.TRACK_BASE_URL ?? "").protocol === "https:"; } catch { return false; }
}

export function alertText(job: AlertJob, env: Env): string {
  const name = job.name || "Your contact";
  if (job.event === "resolved") return `${name} ended their SOS in Security4Her. Contact them if you still have concerns.`;
  if (job.event === "journey_arrived") return `${name} checked in at their journey destination${job.destination ? `: ${job.destination}` : ""}.`;
  if (job.event === "journey_ended") return `${name} ended journey monitoring. This does not confirm arrival.`;
  if (job.event === "journey_started") return `${name} started a monitored journey${job.destination ? ` to ${job.destination}` : ""}. Check-in deadline: ${job.deadline ?? "not available"}. You will be alerted if they miss it.`;
  const link = trackingConfigured(env) ? shareUrl(env, job.token) : null;
  return `${job.trigger === "journey" ? "Missed journey check-in" : "SOS"} from ${name}. They may need help.` +
    (link ? ` Last reported location: ${link}` : " Contact them now.");
}

async function deliver(env: Env, f: Fetch, user: string | null): Promise<void> {
  const token = required(env, "TELEGRAM_BOT_TOKEN");
  const jobs = await rpc<AlertJob[]>(env, f, "ios_claim_alerts", { p_user: user });
  // Batches are capped at ten in SQL. Bound network waits without serializing emergency recipients.
  await Promise.all(jobs.map(async (job) => {
    let sent = false, retry = 60;
    try {
      const text = alertText(job, env);
      const response = await f(`https://api.telegram.org/bot${token}/sendMessage`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ chat_id: job.chatId, text, disable_web_page_preview: true }),
        signal: AbortSignal.timeout(8000),
      });
      const result = await response.json() as { ok?: boolean; parameters?: { retry_after?: number } };
      sent = response.ok && result.ok === true;
      retry = result.parameters?.retry_after ?? (response.status === 403 ? 3600 : 60);
    } catch { /* Keep only the delivery state, never log contact details or message bodies. */ }
    await rpc(env, f, "ios_finish_delivery", { p_id: job.id, p_lease: job.lease, p_sent: sent, p_retry_seconds: retry });
  }));
}

export async function handler(req: Request, env: Env, f: Fetch = fetch): Promise<Response> {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  try {
    if (req.headers.has("x-s4h-dispatch-secret")) {
      if (!env.IOS_DISPATCH_SECRET || req.headers.get("x-s4h-dispatch-secret") !== env.IOS_DISPATCH_SECRET) return json({ error: "unauthorized" }, 401);
      await deliver(env, f, null);
      await rpc(env, f, "ios_worker_heartbeat", {});
      return json({ ok: true });
    }
    const authorization = req.headers.get("authorization");
    if (!authorization?.startsWith("Bearer ")) return json({ error: "sign in required" }, 401);
    const base = required(env, "SUPABASE_URL");
    const apikey = required(env, "SUPABASE_ANON_KEY");
    const authenticated = await f(`${base}/auth/v1/user`, { headers: { apikey, authorization }, signal: AbortSignal.timeout(8000) });
    if (!authenticated.ok) return json({ error: "invalid session" }, 401);
    const user = await authenticated.json() as { id: string };
    const body = await req.json() as Record<string, unknown>;
    const action = body.action;
    if (typeof action !== "string" || !["capabilities", "sos", "end", "journeyStart", "journeyStatus", "journeyExtend", "journeyEnd"].includes(action)) return json({ error: "invalid action" }, 400);
    const deliveryReady = !!env.TELEGRAM_BOT_TOKEN && !!env.SUPABASE_SERVICE_ROLE_KEY && trackingConfigured(env);
    if (action === "journeyStart" && !deliveryReady) return json({ error: "Journey delivery and tracking are not configured." }, 503);
    async function run(): Promise<State> {
      const response = await f(`${base}/rest/v1/rpc/ios_safety`, {
        method: "POST", headers: { apikey, authorization: authorization!, "content-type": "application/json" },
        body: JSON.stringify({ p_action: action, p_data: body }), signal: AbortSignal.timeout(10000),
      });
      if (!response.ok) {
        const result = await response.json() as { message?: string };
        throw new Error(result.message ?? "safety request rejected");
      }
      return await response.json() as State;
    }
    let state = await run();
    if (action === "sos" && env.TELEGRAM_BOT_TOKEN && (state.pending ?? 0) > 0) {
      await deliver(env, f, user.id);
      state = await run();
    }
    return json({ state: state.state, accepted: state.accepted, pending: state.pending,
      schedulerReady: state.schedulerReady, deliveryReady, eligibleRecipients: state.eligibleRecipients,
      deadline: state.deadline, revision: state.revision, incidentState: state.incidentState,
      shareUrl: trackingConfigured(env) ? shareUrl(env, state.shareToken) : null });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "safety service unavailable" }, 400);
  }
}
