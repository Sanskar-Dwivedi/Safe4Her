// GET /functions/v1/digilocker-start?access_token=<user JWT>
// Opened by the phone in a Custom Tab. Redirects to DigiLocker, or back to the app if not configured.
import { type Env, type Fetch, redirect, required, randomToken, seal, sha256B64url } from "../_shared/lib.ts";
import { appReturn, config, isConfigured, type StatePayload } from "../_shared/digilocker.ts";

export async function handler(req: Request, env: Env, f: Fetch = fetch): Promise<Response> {
  const cfg = config(env);
  if (!isConfigured(cfg)) return redirect(appReturn(false, "digilocker_not_configured"));

  const accessToken = new URL(req.url).searchParams.get("access_token");
  if (!accessToken) return redirect(appReturn(false, "missing_session"));

  // Resolve the Supabase user from their access token.
  const userRes = await f(`${required(env, "SUPABASE_URL")}/auth/v1/user`, {
    headers: { apikey: required(env, "SUPABASE_ANON_KEY"), authorization: `Bearer ${accessToken}` },
  });
  if (!userRes.ok) return redirect(appReturn(false, "invalid_session"));
  const user = await userRes.json() as { id?: string };
  if (!user.id) return redirect(appReturn(false, "invalid_session"));

  const verifier = randomToken(48);
  const state: StatePayload = { u: user.id, v: verifier, exp: Date.now() + 10 * 60_000 };
  const url = new URL(cfg.authorizeUrl);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", cfg.clientId!);
  url.searchParams.set("redirect_uri", cfg.redirectUri!);
  url.searchParams.set("state", await seal(cfg.stateSecret!, state));
  url.searchParams.set("code_challenge", await sha256B64url(verifier));
  url.searchParams.set("code_challenge_method", "S256");
  return redirect(url.toString());
}
