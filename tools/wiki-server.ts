import { realpath, readdir } from "node:fs/promises";
import { dirname, extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const headers = {
  "Content-Security-Policy": "default-src 'none'; img-src 'self' https:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
  "X-Content-Type-Options": "nosniff",
};

function page(content: string) {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Folio documentation</title><style>
    :root { color-scheme: light dark; font: 16px/1.65 system-ui, sans-serif; }
    body { max-width: 880px; margin: auto; padding: 1rem 1.5rem 4rem; }
    nav { padding: .5rem 0; border-bottom: 1px solid #8886; }
    a { color: light-dark(#1656a6, #9dc5ff); } img { max-width: 100%; height: auto; }
    h1, h2, h3 { line-height: 1.25; margin-top: 1.7em; } pre { overflow: auto; padding: 1rem; background: #8882; }
    code { font-family: ui-monospace, monospace; } :not(pre) > code { background: #8882; padding: .1em .25em; }
    table { border-collapse: collapse; display: block; overflow-x: auto; } th, td { border: 1px solid #8886; padding: .4rem .7rem; text-align: left; }
    blockquote { border-left: 3px solid #8888; margin-left: 0; padding-left: 1rem; }
  </style></head><body><nav><a href="/docs/wiki/README.md">Folio wiki</a> · <a href="/docs/">All guides</a> · <a href="/README.md">Project README</a></nav><main>${content}</main></body></html>`, {
    headers: { ...headers, "Content-Type": "text/html; charset=utf-8" },
  });
}

export async function handleWiki(request: Request): Promise<Response> {
  const url = new URL(request.url);
  if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) return new Response("Forbidden", { status: 403 });
  if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405 });
  if (url.pathname === "/") return new Response(null, { status: 302, headers: { Location: "/docs/wiki/README.md" } });
  if (url.pathname === "/docs" || url.pathname === "/docs/") {
    const files = (await readdir(resolve(root, "docs"))).filter(name => name.endsWith(".md")).sort();
    return page(`<h1>Engineering guides</h1><ul>${files.map(name => `<li><a href="/docs/${encodeURIComponent(name)}">${name.replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`)}</a></li>`).join("")}</ul>`);
  }

  let path: string;
  try { path = decodeURIComponent(url.pathname); } catch { return new Response("Bad request", { status: 400 }); }
  const candidate = resolve(root, `.${path}`);
  const relative = candidate.slice(root.length + 1).split(sep).join("/");
  const allowed = ["README.md", "AGENTS.md", "release-server/README.md"].includes(relative) ||
    (relative.startsWith("docs/") && [".md", ".png", ".jpg", ".jpeg", ".svg"].includes(extname(relative).toLowerCase()));
  if (!candidate.startsWith(root + sep) || !allowed) return new Response("Not found", { status: 404 });
  try {
    const actual = await realpath(candidate);
    if (!actual.startsWith(root + sep)) return new Response("Not found", { status: 404 });
    const file = Bun.file(actual);
    if (!(await file.exists())) return new Response("Not found", { status: 404 });
    if (extname(actual).toLowerCase() === ".md") {
      return page(Bun.markdown.html(await file.text(), { headings: { ids: true }, tagFilter: true }));
    }
    return new Response(file, { headers });
  } catch {
    return new Response("Not found", { status: 404 });
  }
}

if (import.meta.main) {
  const server = Bun.serve({ hostname: "127.0.0.1", port: Number(process.env.PORT ?? 3000), fetch: handleWiki });
  console.log(`Folio wiki: ${server.url}`);
}
