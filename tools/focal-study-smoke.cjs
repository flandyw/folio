// Run after an Android build has populated the Kotlin compiler cache and app classes.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run, latestAppClasses } = require('./kotlin-smoke.cjs');
const appClasses = latestAppClasses();
if (!appClasses) throw new Error('An Android build must populate app classes before the study smoke check.');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const runtime = [appClasses, stdlib, json];
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-study-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  // The session models, recovery rules and command helpers live apart from the Android manager.
  const pure = ['study/FocalStudySessions.kt', 'study/FocalSubjects.kt', 'study/FocalStudyEdits.kt',
    'study/FocalFocus.kt', 'study/FocalStudySync.kt'].map(file => path.join(source, file));
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', runtime.join(path.delimiter), `-Xfriend-paths=${appClasses}`, '-d', classes,
    path.join(source, 'exam/ExamTimer.kt'), ...pure, path.join(__dirname, 'FocalStudySmoke.kt')]);
  run(['-cp', [classes, ...runtime].join(path.delimiter), 'com.folio.notes.FocalStudySmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
