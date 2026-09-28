// Minimal Deno ambient types so `tsc` can type-check the functions outside Deno.
declare namespace Deno {
  function serve(handler: (req: Request) => Response | Promise<Response>): unknown;
  const env: { get(key: string): string | undefined; toObject(): Record<string, string> };
}
