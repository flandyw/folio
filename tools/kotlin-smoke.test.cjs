const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { root, latestAppClasses } = require('./kotlin-smoke.cjs');

test('app classes come from the newest available build', t => {
  const builds = new Map();
  t.mock.method(fs, 'existsSync', file => builds.has(file));
  t.mock.method(fs, 'statSync', file => ({ mtimeMs: builds.get(file) }));
  const release = path.join(root, 'app/build/intermediates/compile_app_classes_jar/release/bundleReleaseClassesToCompileJar/classes.jar');
  const debug = path.join(root, 'app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar');

  assert.equal(latestAppClasses(), undefined);
  builds.set(debug, 100);
  assert.equal(latestAppClasses(), debug);
  builds.set(release, 200);
  assert.equal(latestAppClasses(), release);
  builds.set(debug, 300);
  assert.equal(latestAppClasses(), debug);
  builds.delete(debug);
  assert.equal(latestAppClasses(), release);
});
