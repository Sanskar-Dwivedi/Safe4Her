// POST /functions/v1/watch-sos — the watch's direct (Wi-Fi) SOS path. See CONTRACT.md §3.
// Auth: x-device-token header (from register_watch()); JWT verification is off for this function.
import { type Env, type Fetch, json, rpc, RpcError, shareUrl, telegramSend, UUID_RE } from "../_shared/lib.ts";

interface Body {
  action?: string;
  incidentId?: string;
  trigger?: string;
  lat?: number;
  lng?: number;
  accuracyM?: number;
  fixAt?: number;
  test?: boolean;
}

interface WatchSosResult {
  created: boolean;
  status: string | null;
  share_token: string | null;
  display_name: string | null;
  contacts: { name: string; phone: string; telegram_chat_id: number | null }[];
}

export async function handler(req: Request, env: Env, f: Fetch = fetch): Promise<Response> {
  if (req.method !== "POST") return json({ ok: false, error: "method not allowed" }, 405);
  const token = req.headers.get("x-device-token");
  if (!token) return json({ ok: false, error: "missing device token" }, 401);

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "invalid json" }, 400);
  }
  const action = body.action;
  if (action !== "start" && action !== "location" && action !== "cancel") {
    return json({ ok: false, error: "invalid action" }, 400);
  }
  if (!body.incidentId || !UUID_RE.test(body.incidentId)) {
    return json({ ok: false, error: "invalid incidentId" }, 400);
  }
  const hasFix = typeof body.lat === "number" && typeof body.lng === "number";
  if (action === "location" && !hasFix) return json({ ok: false, error: "lat/lng required" }, 400);

  let result: WatchSosResult;
  try {
    result = await rpc<WatchSosResult>(env, f, "watch_sos", {
      p_device_token: token,
      p_action: action,
      p_incident_id: body.incidentId,
      p_trigger: body.trigger ?? "knock",
      p_lat: hasFix ? body.lat : null,
      p_lng: hasFix ? body.lng : null,
      p_acc: typeof body.accuracyM === "number" ? body.accuracyM : null,
      p_fix_at: typeof body.fixAt === "number" ? new Date(body.fixAt).toISOString() : null,
      p_test: body.test ?? true,
    });
  } catch (e) {
    if (e instanceof RpcError) {
      if (e.message.includes("invalid device token")) return json({ ok: false, error: "invalid device token" }, 401);
      if (e.message.includes("not verified")) return json({ ok: false, error: "not verified" }, 403);
      if (e.message.includes("not found")) return json({ ok: false, error: "incident not found" }, 404);
      if (e.message.includes("another user")) return json({ ok: false, error: "forbidden" }, 403);
      if (e.status >= 400 && e.status < 500) return json({ ok: false, error: e.message }, 400);
    }
    console.error("watch_sos rpc failed", e);
    return json({ ok: false, error: "server error" }, 502);
  }

  const url = shareUrl(env, result.share_token);
  let telegramSent = 0;
  const notify = (action === "start" && result.created) || action === "cancel";
  if (notify) {
    const name = result.display_name || "Your contact";
    const testTag = body.test === false ? "" : "[TEST MODE] ";
    let text: string;
    if (action === "start") {
      const maps = hasFix ? `\nLast seen: https://maps.google.com/?q=${body.lat},${body.lng}` +
        (typeof body.accuracyM === "number" ? ` (±${Math.round(body.accuracyM)} m)` : "") : "";
      text = `${testTag}🚨 SOS from ${name} (sent from her watch). She may be in danger.` +
        (url ? `\nLive location: ${url}` : "") + maps +
        `\nCall her, and call 112 if you can't reach her.`;
    } else {
      text = `${testTag}✅ ${name} has marked herself safe. Live location sharing has stopped.`;
    }
    const chats = result.contacts.map((c) => c.telegram_chat_id).filter((id): id is number => id != null);
    const sent = await Promise.all(chats.map((id) => telegramSend(env, f, id, text)));
    telegramSent = sent.filter(Boolean).length;
  }

  return json({ ok: true, created: result.created, status: result.status, shareUrl: url, telegramSent });
}
