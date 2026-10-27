import { handleWiki } from "./wiki-server";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const port = Number(process.env.PORT ?? 3000);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT must be between 1 and 65535");
if (!Bun.which("cloudflared")) throw new Error("Install cloudflared and add it to PATH");

const server = Bun.serve({ hostname: "127.0.0.1", port, fetch: handleWiki });
console.log(`Folio wiki: ${server.url}`);
console.log("Starting a public quick tunnel. Press Ctrl+C to stop both services.");

try {
  const dir = await mkdtemp(join(tmpdir(), "folio-wiki-tunnel-"));
  try {
    const config = join(dir, "config.yml");
    await writeFile(config, "{}\n");
    const tunnel = Bun.spawn([
      "cloudflared", "tunnel", "--config", config, "--url", `http://127.0.0.1:${port}`,
      "--http-host-header", `localhost:${port}`,
    ], { stdout: "inherit", stderr: "inherit" });
    let interrupted = false;
    const stop = () => { interrupted = true; tunnel.kill(); };
    process.once("SIGINT", stop);
    process.once("SIGTERM", stop);
    const code = await tunnel.exited;
    process.removeListener("SIGINT", stop);
    process.removeListener("SIGTERM", stop);
    if (!interrupted) process.exitCode = code;
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
} finally {
  server.stop(true);
}
