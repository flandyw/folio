// Native gesture/overlay regressions with minimal Android stand-ins. Run after prepareBackupSmoke.
// Run after the canonical build with :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke.
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
  throw new Error(`Missing cached ${module} ${version}; run ./gradlew :app:assembleDebug first.`);
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
  if (result.status !== 0) throw new Error(`Sticky-note check failed (exit ${result.status}).`);
}
const appClasses = [
  'release/bundleReleaseClassesToCompileJar', 'debug/bundleDebugClassesToCompileJar',
].map(variant => path.join(root, 'app/build/intermediates/compile_app_classes_jar', variant, 'classes.jar'))
  .filter(file => fs.existsSync(file))
  .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0];
if (!appClasses) throw new Error('Run ./build.sh -p -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke before the backup smoke check.');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const runtime = [appClasses, stdlib, json];
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-sticky-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  // Exercise the actual native overlay logic without loading Compose or an Android runtime.
  // Platform stand-ins model coordinates/events; they do not establish device rendering results.
  const overlay = fs.readFileSync(path.join(source, 'editor/sticky/DocumentStickyWorkspace.kt'), 'utf8');
  const projectImports = overlay.match(/^import com\.folio\.notes\..*$/gm).join('\n');
  const native = overlay.slice(overlay.indexOf('private class StickyWorkspaceView'))
    .replace('private class StickyWorkspaceView', 'internal class StickyWorkspaceView');
  const overlayFile = path.join(temp, 'StickyWorkspaceView.kt');
  fs.writeFileSync(overlayFile, `package com.folio.notes.editor.sticky
${projectImports}
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Rect
${native}`);
  const stubs = path.join(__dirname, 'sticky-smoke');
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn',
    '-classpath', runtime.join(path.delimiter), `-Xfriend-paths=${appClasses}`, '-d', classes,
    ...['editor/sticky/StickyNotes.kt', 'editor/sticky/StickyNoteInput.kt', 'model/PageLayers.kt'].map(file => path.join(source, file)),
    ...fs.readdirSync(stubs).filter(file => file.endsWith('.kt')).map(file => path.join(stubs, file)),
    overlayFile, path.join(__dirname, 'StickyNotesSmoke.kt')]);
  run(['-cp', [classes, ...runtime].join(path.delimiter), 'com.folio.notes.StickyNotesSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
