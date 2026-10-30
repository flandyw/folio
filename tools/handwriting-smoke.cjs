// Run after an Android build has populated Gradle's Kotlin compiler cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, compiler, dependencies, annotations, stdlib, run } = require('./kotlin-smoke.cjs');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-handwriting-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  // model/Ink.kt holds the pure ink primitives (tools, points, strokes) and has no Android imports.
  const primitive = path.join(source, 'model/Ink.kt');
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes,
    ...['ink/pen/PenPressure.kt', 'ink/pen/StrokePrediction.kt', 'ink/InkTaper.kt', 'ink/pen/StrokeRefit.kt', 'ink/recognition/HandwritingTidy.kt', 'ink/InkOutline.kt', 'ink/pen/StrokeEnds.kt'].map(file => path.join(source, file)),
    primitive, path.join(__dirname, 'HandwritingSmoke.kt')]);
  run(['-cp', [classes, stdlib].join(path.delimiter), 'com.folio.notes.HandwritingSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
