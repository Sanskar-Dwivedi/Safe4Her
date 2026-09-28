// Telegram bot webhook: a trusted contact sends "/start <code>" to link their chat for SOS alerts.
// Register with: https://api.telegram.org/bot<TOKEN>/setWebhook?url=<fn url>&secret_token=<TELEGRAM_WEBHOOK_SECRET>
import { type Env, type Fetch, json, rpc, telegramSend } from "../_shared/lib.ts";

interface Update {
  message?: { chat?: { id?: number }; text?: string };
}

export async function handler(req: Request, env: Env, f: Fetch = fetch): Promise<Response> {
  if (req.method !== "POST") return json({ ok: false }, 405);
  const secret = env.TELEGRAM_WEBHOOK_SECRET;
  if (!secret || req.headers.get("x-telegram-bot-api-secret-token") !== secret) {
    return json({ ok: false }, 401);
  }

  let update: Update;
  try {
    update = await req.json();
  } catch {
    return json({ ok: true }); // never make Telegram retry garbage
  }
  const chatId = update.message?.chat?.id;
  const text = update.message?.text?.trim() ?? "";
  if (chatId == null) return json({ ok: true });

  const m = text.match(/^\/start(?:@\w+)?\s+([0-9a-f]{8})\b/i);
  if (!m) {
    await telegramSend(env, f, chatId,
      "Hi! This bot sends Security4Her SOS alerts.\nAsk the person who added you for her 8-character code, then send: /start <code>");
    return json({ ok: true });
  }

  try {
    const linked = await rpc<{ contact_name: string; owner_name: string } | null>(
      env, f, "telegram_link", { p_code: m[1].toLowerCase(), p_chat_id: chatId });
    if (linked) {
      await telegramSend(env, f, chatId,
        `✅ Linked. You (${linked.contact_name}) will get SOS alerts from ${linked.owner_name || "your contact"} here, ` +
        `including when her phone is unreachable.`);
    } else {
      await telegramSend(env, f, chatId, "That code wasn't found. Check it and try again.");
    }
  } catch (e) {
    console.error("telegram_link failed", e);
    await telegramSend(env, f, chatId, "Sorry, something went wrong. Please try again in a minute.");
  }
  return json({ ok: true });
}
