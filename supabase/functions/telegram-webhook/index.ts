import { handler } from "./handler.ts";

Deno.serve((req) => handler(req, Deno.env.toObject()));
