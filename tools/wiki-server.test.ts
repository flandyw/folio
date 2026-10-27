import { describe, expect, test } from "bun:test";
import { handleWiki } from "./wiki-server";

const get = (path: string) => handleWiki(new Request(`http://localhost:3000${path}`));

describe("wiki server", () => {
  test("redirects to the wiki and renders headings, links and tables", async () => {
    expect((await get("/")).headers.get("location")).toBe("/docs/wiki/README.md");
    const response = await get("/docs/wiki/development.md");
    const html = await response.text();
    expect(response.status).toBe(200);
    expect(html).toContain('id="architecture"');
    expect(html).toContain('<a href="../application-architecture.md">');
    expect(html).toContain("<table>");
  });

  test("serves linked guides and images, and lists guides", async () => {
    expect((await get("/docs/document-transactions.md")).status).toBe(200);
    expect((await get("/README.md")).status).toBe(200);
    expect((await get("/docs/images/folio-banner.png")).status).toBe(200);
    expect(await (await get("/docs/")).text()).toContain("document-transactions.md");
  });

  test("does not expose unrelated files or accept other hosts", async () => {
    expect((await get("/.signing/password")).status).toBe(404);
    expect((await get("/docs/%2e%2e/.env")).status).toBe(404);
    expect((await get("/docs/missing.md")).status).toBe(404);
    expect((await handleWiki(new Request("http://example.com/README.md"))).status).toBe(403);
    expect((await handleWiki(new Request("http://localhost/", { method: "POST" }))).status).toBe(405);
  });
});
