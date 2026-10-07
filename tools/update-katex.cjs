#!/usr/bin/env node
// Maintains the offline bundle; needs Node 18+ and tar, no npm install.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const repo = path.resolve(__dirname, '..');
const assets = path.join(repo, 'app/src/main/assets/katex');
const renderer = path.join(repo, 'app/src/main/java/com/folio/notes/math/KaTeXMath.kt');
const metadataUrl = 'https://registry.npmjs.org/katex/latest';
const stableVersion = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

async function download(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(60_000) });
  if (!response.ok) throw new Error(`Download failed: HTTP ${response.status} (${url})`);
  return Buffer.from(await response.arrayBuffer());
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && ['--help', '-h'].includes(args[0])) {
    console.log(`Usage: node tools/update-katex.cjs [--check | --update]
  --check   Report the bundled and latest stable versions (default); change no files.
  --update  Verify, smoke-test and install a newer stable bundle and bump the render cache.
Requires Node 18+, tar and internet access. Exits 0 on success, 1 on error.`);
    return;
  }
  if (args.length > 1 || (args.length && !['--check', '--update'].includes(args[0]))) {
    throw new Error('Usage: node tools/update-katex.cjs [--check | --update]');
  }
  const current = fs.readFileSync(path.join(assets, 'VERSION.txt'), 'utf8')
    .match(/^KaTeX (\d+\.\d+\.\d+)\r?$/m)?.[1];
  if (!current || !stableVersion.test(current)) throw new Error('Invalid bundled VERSION.txt');
  const metadata = JSON.parse((await download(metadataUrl)).toString('utf8'));
  const latest = metadata.version;
  if (metadata.name !== 'katex' || !stableVersion.test(latest)) {
    throw new Error('Registry did not return a stable KaTeX release');
  }
  console.log(`Bundled KaTeX: ${current}\nLatest stable: ${latest}`);
  const before = current.split('.').map(Number);
  const after = latest.split('.').map(Number);
  const difference = after.map((value, i) => value - before[i]).find(value => value !== 0) || 0;
  if (difference <= 0) {
    console.log(difference === 0 ? 'KaTeX is up to date.' : 'Bundled KaTeX is newer; no downgrade.');
    return;
  }
  console.log(`Update available: ${current} -> ${latest}`);
  console.log(`Release notes: https://github.com/KaTeX/KaTeX/releases/tag/v${latest}`);
  if (args[0] !== '--update') {
    console.log('Apply with: node tools/update-katex.cjs --update');
    return;
  }

  const source = metadata.dist?.tarball;
  const integrity = metadata.dist?.integrity;
  if (source !== `https://registry.npmjs.org/katex/-/katex-${latest}.tgz` ||
      !/^sha512-[A-Za-z0-9+/]+={0,2}$/.test(integrity || '')) {
    throw new Error('Missing or unexpected package URL/SHA-512 integrity');
  }
  const archive = await download(source);
  const digest = crypto.createHash('sha512').update(archive).digest('base64');
  if (`sha512-${digest}` !== integrity) throw new Error('Package SHA-512 integrity mismatch');

  const originalRenderer = fs.readFileSync(renderer, 'utf8');
  const cachePattern = /private const val KATEX_DISK_VERSION = "katex-(\d+\.\d+\.\d+)([^"]*)"/g;
  const caches = [...originalRenderer.matchAll(cachePattern)];
  if (caches.length !== 1 || caches[0][1] !== current) {
    throw new Error('Renderer cache version does not match the bundled KaTeX version');
  }
  const updatedRenderer = originalRenderer.replace(cachePattern,
    (_, version, suffix) => `private const val KATEX_DISK_VERSION = "katex-${latest}${suffix}"`);

  // Stage beside the bundle so the final directory renames stay on one filesystem.
  const work = fs.mkdtempSync(path.join(path.dirname(assets), '.katex-update-'));
  const staged = path.join(work, 'staged');
  const backup = path.join(work, 'previous');
  let movedOriginal = false;
  let installed = false;
  let complete = false;
  try {
    const archivePath = path.join(work, 'katex.tgz');
    fs.writeFileSync(archivePath, archive);
    // Read specific members to stdout instead of extracting archive paths or links.
    const member = name => execFileSync('tar', ['-xOf', archivePath, `package/${name}`],
      { maxBuffer: 16 * 1024 * 1024 });
    const manifest = JSON.parse(member('package.json').toString('utf8'));
    if (manifest.name !== 'katex' || manifest.version !== latest || manifest.license !== 'MIT') {
      throw new Error('Package manifest does not match the requested KaTeX release');
    }
    fs.cpSync(assets, staged, { recursive: true });
    fs.rmSync(path.join(staged, 'fonts'), { recursive: true });
    fs.mkdirSync(path.join(staged, 'fonts'));
    for (const name of ['katex.min.js', 'katex.min.css']) {
      fs.writeFileSync(path.join(staged, name), member(`dist/${name}`));
    }
    fs.writeFileSync(path.join(staged, 'LICENSE'), member('LICENSE'));
    const css = fs.readFileSync(path.join(staged, 'katex.min.css'), 'utf8');
    const fonts = new Set([...css.matchAll(/url\(([^)]+)\)/g)]
      .map(match => match[1].replace(/["']/g, '')));
    if (!fonts.size) throw new Error('Stylesheet has no font URLs');
    for (const font of fonts) {
      if (!/^fonts\/KaTeX_[\w-]+\.(woff2|woff|ttf)$/.test(font)) {
        throw new Error(`Unsupported font URL: ${font}`);
      }
      fs.writeFileSync(path.join(staged, font), member(`dist/${font}`));
    }
    fs.writeFileSync(path.join(staged, 'VERSION.txt'),
      `KaTeX ${latest}\nSource: ${source}\nIntegrity: ${integrity}\n` +
      'License: MIT (see LICENSE)\nAssets are unmodified; CSS fonts/ URLs resolve locally.\n');
    if (require(path.join(staged, 'katex.min.js')).version !== latest) {
      throw new Error('JavaScript version does not match the requested release');
    }
    execFileSync(process.execPath, [path.join(__dirname, 'katex-smoke.cjs'), staged], { stdio: 'inherit' });

    fs.renameSync(assets, backup);
    movedOriginal = true;
    fs.renameSync(staged, assets);
    installed = true;
    fs.writeFileSync(renderer, updatedRenderer);
    complete = true;
    console.log(`Updated KaTeX to ${latest}; offline assets and bitmap cache version are in sync.`);
  } finally {
    if (!complete && movedOriginal) {
      if (installed) fs.rmSync(assets, { recursive: true });
      fs.renameSync(backup, assets);
      fs.writeFileSync(renderer, originalRenderer);
    }
    fs.rmSync(work, { recursive: true, force: true });
  }
}

main().catch(error => {
  console.error(`KaTeX update failed: ${error.message}`);
  process.exitCode = 1;
});
