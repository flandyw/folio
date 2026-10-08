// Run after an Android build has populated Gradle's Kotlin compiler cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const cache = path.join(process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle'), 'caches/modules-2/files-2.1');
const version = fs.readFileSync(path.join(root, 'build.gradle.kts'), 'utf8')
  .match(/id\("org\.jetbrains\.kotlin\.plugin\.compose"\) version "([^"]+)"/)[1];
function cached(group, module, version, extension = '.jar', compatible = false) {
  const parent = path.join(cache, group, module);
  const versions = [version];
  // Gradle can upgrade transitive runtime dependencies while keeping the compiler pinned.
  if (compatible && fs.existsSync(parent)) {
    versions.push(...fs.readdirSync(parent).filter(v => v !== version)
      .sort((a, b) => b.localeCompare(a, 'en', { numeric: true })));
  }
  for (const candidate of versions) {
    const dir = path.join(parent, candidate);
    if (!fs.existsSync(dir)) continue;
    for (const hash of fs.readdirSync(dir)) {
      const file = path.join(dir, hash, `${module}-${candidate}${extension}`);
      if (fs.existsSync(file)) return file;
    }
  }
  throw new Error(`Missing cached ${module} ${version}; run ./build.sh -p first.`);
}
const compiler = cached('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', version);
const pom = fs.readFileSync(cached('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', version, '.pom'), 'utf8');
const dependencies = [...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(([, text]) => {
  const field = name => text.match(new RegExp(`<${name}>([^<]+)</${name}>`))[1];
  return cached(field('groupId'), field('artifactId'), field('version'), '.jar', true);
});
const annotations = cached('org.jetbrains', 'annotations', '13.0', '.jar', true);
const stdlib = cached('org.jetbrains.kotlin', 'kotlin-stdlib', version);
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
function run(args) {
  const result = spawnSync(java, args, { stdio: 'inherit', cwd: root });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Palm rejection check failed (exit ${result.status}).`);
}
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-palm-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes,
    ...['PalmRejection.kt', 'TouchChord.kt'].map(file => path.join(source, file)),
    path.join(__dirname, 'PalmRejectionSmoke.kt')]);
  run(['-cp', [classes, stdlib].join(path.delimiter), 'com.folio.notes.PalmRejectionSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
