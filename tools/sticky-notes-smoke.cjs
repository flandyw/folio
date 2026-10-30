// Native gesture/overlay regressions with minimal Android stand-ins. Run after prepareBackupSmoke.
// Run after the canonical build with :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run, latestAppClasses } = require('./kotlin-smoke.cjs');
const appClasses = latestAppClasses();
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
