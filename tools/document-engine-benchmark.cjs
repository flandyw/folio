// Desktop CPU/allocation baseline. Run after a build with :app:prepareBackupSmoke.
// Optionally pass a saved classes.jar as the first argument to compare builds.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run, latestAppClasses } = require('./kotlin-smoke.cjs');
const appClasses = process.argv[2] ? path.resolve(process.argv[2]) : latestAppClasses();
if (!appClasses || !fs.existsSync(appClasses)) throw new Error('Provide an existing classes.jar or run ./build.sh -p -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke before the document engine benchmark.');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const coroutines = cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0', '.jar', true);
const runtime = [appClasses, stdlib, json, coroutines];
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-document-benchmark-'));
try {
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', runtime.join(path.delimiter), `-Xfriend-paths=${appClasses}`, '-d', classes,
    path.join(__dirname, 'DocumentEngineBenchmark.kt')]);
  run(['-cp', [classes, ...runtime].join(path.delimiter), 'com.folio.notes.DocumentEngineBenchmarkKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
