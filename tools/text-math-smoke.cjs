// Run after an Android build has populated Gradle's Kotlin compiler cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, compiler, dependencies, annotations, stdlib, run } = require('./kotlin-smoke.cjs');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-text-math-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes,
    ...['math/TextMath.kt'].map(file => path.join(source, file)),
    path.join(__dirname, 'TextMathSmoke.kt')]);
  run(['-cp', [classes, stdlib].join(path.delimiter), 'com.folio.notes.TextMathSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
