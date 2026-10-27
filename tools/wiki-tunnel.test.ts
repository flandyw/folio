import { expect, test } from "bun:test";
import { mkdtemp, rm, writeFile, chmod } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const script = join(import.meta.dir, "wiki-tunnel.ts");

test("invalid ports fail before starting a tunnel", () => {
  const result = Bun.spawnSync([process.execPath, script], { env: { ...process.env, PORT: "0" } });
  expect(result.exitCode).not.toBe(0);
  expect(result.stderr.toString()).toContain("PORT must be between 1 and 65535");
});

test("tunnel exit closes the server and propagates failure", async () => {
  const dir = await mkdtemp(join(tmpdir(), "folio-wiki-test-"));
  const reservation = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: () => new Response() });
  const port = reservation.port!;
  reservation.stop(true);
  try {
    const executable = join(dir, "cloudflared");
    await writeFile(executable, "#!/bin/sh\nexit 7\n");
    await chmod(executable, 0o755);
    const result = Bun.spawnSync([process.execPath, script], {
      env: { ...process.env, PORT: String(port), PATH: `${dir}:${process.env.PATH}` },
    });
    expect(result.exitCode).toBe(7);
    const check = Bun.serve({ hostname: "127.0.0.1", port, fetch: () => new Response() });
    check.stop(true);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
