// Run after ./gradlew :app:prepareBackupSmoke has populated the JVM cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run, latestAppClasses } = require('./kotlin-smoke.cjs');
const appClasses = latestAppClasses();
if (!appClasses) throw new Error('Run ./gradlew :app:prepareBackupSmoke before the marking smoke check.');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const coroutines = cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0', '.jar', true);
const runtime = [appClasses, stdlib, json, coroutines];
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-marking-'));
try {
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', runtime.join(path.delimiter), `-Xfriend-paths=${appClasses}`, '-d', classes,
    path.join(__dirname, 'MarkingSmoke.kt')]);
  run(['-cp', [classes, ...runtime].join(path.delimiter), 'com.folio.notes.MarkingSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
