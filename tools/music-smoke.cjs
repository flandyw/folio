// Run after an Android build has populated Gradle's Kotlin compiler cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run } = require('./kotlin-smoke.cjs');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-music-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes/music/MusicModels.kt');
  const classes = path.join(temp, 'classes');
  const runtime = [stdlib, json].join(path.delimiter);
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', classes,
    source, path.join(root, 'app/src/main/java/com/folio/notes/music/MusicParts.kt'),
    path.join(root, 'app/src/main/java/com/folio/notes/music/MusicShelf.kt'), path.join(__dirname, 'MusicSmoke.kt')]);
  run(['-cp', [classes, runtime].join(path.delimiter), 'com.folio.notes.music.MusicSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
