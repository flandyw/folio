// Run after an Android build has populated Gradle's Kotlin compiler cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, compiler, dependencies, annotations, stdlib, run } = require('./kotlin-smoke.cjs');
// `--accuracy [-v]` runs the synthetic hand-drawn accuracy report instead of the regression checks.
const accuracy = process.argv.includes('--accuracy');
const passthrough = process.argv.slice(2).filter(a => a !== '--accuracy');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-shapes-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  // model/Ink.kt holds the pure ink primitives (tools, points, strokes) and has no Android imports.
  const primitive = path.join(source, 'model/Ink.kt');
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes,
    ...['ink/shapes/ShapeFit.kt', 'ink/shapes/ShapeRecognition.kt', 'ink/shapes/ShapeHold.kt', 'ink/pen/PenStartHold.kt', 'ink/shapes/ShapeAssist.kt', 'ink/shapes/ShapeCleanup.kt', 'ink/shapes/ShapeAdjustment.kt'].map(file => path.join(source, file)),
    primitive, path.join(__dirname, accuracy ? 'ShapeAccuracy.kt' : 'ShapeSmoke.kt')]);
  run(['-cp', [classes, stdlib].join(path.delimiter), accuracy ? 'com.folio.notes.ShapeAccuracyKt' : 'com.folio.notes.ShapeSmokeKt', ...passthrough]);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
