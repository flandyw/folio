// Shared cached Kotlin toolchain for the standalone smoke checks.
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
  throw new Error(`Missing cached ${module} ${version}; run the canonical build and :app:prepareBackupSmoke first.`);
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
  if (result.status !== 0) throw new Error(`Kotlin smoke command failed (exit ${result.status}).`);
}

// Use the newest build so a release check never silently reads stale debug classes.
function latestAppClasses() {
  return [
    'release/bundleReleaseClassesToCompileJar', 'debug/bundleDebugClassesToCompileJar',
  ].map(variant => path.join(root, 'app/build/intermediates/compile_app_classes_jar', variant, 'classes.jar'))
    .filter(file => fs.existsSync(file))
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0];
}

module.exports = { root, cached, compiler, dependencies, annotations, stdlib, run, latestAppClasses };
