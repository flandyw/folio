// Run after ./gradlew :app:assembleDebug :app:prepareBackupSmoke. Optional argument: server manifest JSON.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run, latestAppClasses } = require('./kotlin-smoke.cjs');
const appClasses = latestAppClasses();
if (!appClasses) throw new Error('Run ./gradlew :app:assembleDebug :app:prepareBackupSmoke before the update smoke check.');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const runtime = [appClasses, stdlib, json];
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-updates-'));
try {
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', runtime.join(path.delimiter), `-Xfriend-paths=${appClasses}`, '-d', classes,
    path.join(__dirname, 'UpdateSmoke.kt')]);
  run(['-cp', [classes, ...runtime].join(path.delimiter), 'com.folio.notes.UpdateSmokeKt', ...process.argv.slice(2)]);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
