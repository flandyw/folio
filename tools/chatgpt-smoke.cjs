// Run after an Android build has populated Gradle's Kotlin compiler cache.
// No device, Android runtime, extra downloads, or test framework is needed.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { root, cached, compiler, dependencies, annotations, stdlib, run } = require('./kotlin-smoke.cjs');
const json = cached('org.json', 'json', '20240303', '.jar', true);
const okhttp = cached('com.squareup.okhttp3', 'okhttp', '4.12.0', '.jar', true);
const okio = cached('com.squareup.okio', 'okio-jvm', '3.6.0', '.jar', true);
const runtime = [stdlib, json, okhttp, okio].join(path.delimiter);
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-chatgpt-'));
try {
  const source = path.join(root, 'app/src/main/java/com/folio/notes');
  const classes = path.join(temp, 'classes');
  run(['-cp', [compiler, ...dependencies, annotations].join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', classes,
    path.join(source, 'chatgpt/ChatGptProtocol.kt'),
    path.join(source, 'chatgpt/ChatGptInference.kt'),
    path.join(source, 'chatgpt/ChatGptReview.kt'),
    path.join(source, 'chatgpt/ChatGptTransport.kt'),
    path.join(__dirname, 'ChatGptSmoke.kt')]);
  run(['-cp', [classes, runtime].join(path.delimiter), 'com.folio.notes.chatgpt.ChatGptSmokeKt']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
