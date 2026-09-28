// GET /functions/v1/digilocker-callback?code=...&state=...  (DigiLocker redirect target)
// Exchanges the code, reads gender only, marks the profile verified, and returns to the app.
import { type Env, type Fetch, redirect, rpc, sha256Hex, unseal } from "../_shared/lib.ts";
import { appReturn, config, extractGender, isConfigured, type StatePayload } from "../_shared/digilocker.ts";

export async function handler(req: Request, env: Env, f: Fetch = fetch): Promise<Response> {
  const cfg = config(env);
  if (!isConfigured(cfg)) return redirect(appReturn(false, "digilocker_not_configured"));

  const params = new URL(req.url).searchParams;
  if (params.get("error")) return redirect(appReturn(false, "user_denied"));
  const code = params.get("code");
  const state = await unseal<StatePayload>(cfg.stateSecret!, params.get("state") ?? "");
  if (!code || !state || state.exp < Date.now()) return redirect(appReturn(false, "invalid_state"));

  let tokenJson: Record<string, unknown>;
  try {
    const tokenRes = await f(cfg.tokenUrl, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code,
        client_id: cfg.clientId!,
        client_secret: cfg.clientSecret!,
        redirect_uri: cfg.redirectUri!,
        code_verifier: state.v,
      }),
    });
    if (!tokenRes.ok) return redirect(appReturn(false, "token_exchange_failed"));
    tokenJson = await tokenRes.json();
  } catch {
    return redirect(appReturn(false, "token_exchange_failed"));
  }

  let gender = extractGender(tokenJson);
  if (!gender && typeof tokenJson.access_token === "string") {
    try {
      const x = await f(cfg.eaadhaarUrl, { headers: { authorization: `Bearer ${tokenJson.access_token}` } });
      if (x.ok) gender = extractGender({}, await x.text());
    } catch { /* fall through */ }
  }
  if (!gender) return redirect(appReturn(false, "gender_unavailable"));

  const subject = String(tokenJson.digilockerid ?? tokenJson.sub ?? "");
  if (!subject) return redirect(appReturn(false, "no_subject"));

  try {
    const isFemale = gender === "F";
    await rpc(env, f, "set_verified_from_digilocker", {
      p_user: state.u, p_is_female: isFemale, p_sub_hash: await sha256Hex(`digilocker:${subject}`),
    });
    return redirect(appReturn(isFemale, isFemale ? undefined : "not_eligible"));
  } catch (e) {
    const msg = e instanceof Error ? e.message : "";
    return redirect(appReturn(false, msg.includes("already linked") ? "already_linked" : "server_error"));
  }
}
