// DigiLocker OAuth 2.0 (authorization code + PKCE) for women-only verification.
// Only the gender is used; the Aadhaar number and other data are never stored.
// Endpoint URLs are configurable because they must be confirmed against the current
// DigiLocker / API Setu partner documentation for your Requester registration.
import type { Env } from "./lib.ts";

export const APP_RETURN = "security4her://verified";

export interface DigiLockerConfig {
  mode: string;
  clientId?: string;
  clientSecret?: string;
  redirectUri?: string;
  authorizeUrl: string;
  tokenUrl: string;
  eaadhaarUrl: string;
  stateSecret?: string;
}

export function config(env: Env): DigiLockerConfig {
  return {
    mode: env.DIGILOCKER_MODE ?? "mock",
    clientId: env.DIGILOCKER_CLIENT_ID,
    clientSecret: env.DIGILOCKER_CLIENT_SECRET,
    redirectUri: env.DIGILOCKER_REDIRECT_URI, // = https://<project>.supabase.co/functions/v1/digilocker-callback
    authorizeUrl: env.DIGILOCKER_AUTHORIZE_URL ?? "https://digilocker.meripehchaan.gov.in/public/oauth2/1/authorize",
    tokenUrl: env.DIGILOCKER_TOKEN_URL ?? "https://digilocker.meripehchaan.gov.in/public/oauth2/1/token",
    eaadhaarUrl: env.DIGILOCKER_EAADHAAR_URL ?? "https://digilocker.meripehchaan.gov.in/public/oauth2/3/xml/eaadhaar",
    stateSecret: env.DIGILOCKER_STATE_SECRET,
  };
}

export function isConfigured(c: DigiLockerConfig): boolean {
  return c.mode === "digilocker" && !!c.clientId && !!c.clientSecret && !!c.redirectUri && !!c.stateSecret;
}

export function appReturn(ok: boolean, reason?: string): string {
  return `${APP_RETURN}?ok=${ok ? 1 : 0}${reason ? `&reason=${encodeURIComponent(reason)}` : ""}`;
}

/** Extracts gender (M/F/T) from a token response or an e-Aadhaar XML document. */
export function extractGender(tokenResponse: Record<string, unknown>, eaadhaarXml?: string): string | null {
  const g = tokenResponse.gender;
  if (typeof g === "string" && g.trim()) return g.trim().charAt(0).toUpperCase();
  if (eaadhaarXml) {
    const m = eaadhaarXml.match(/\bgender\s*=\s*"([MFT])"/i);
    if (m) return m[1].toUpperCase();
  }
  return null;
}

export interface StatePayload {
  u: string;   // Supabase user id
  v: string;   // PKCE code verifier
  exp: number; // epoch ms
}
