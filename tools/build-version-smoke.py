#!/usr/bin/env python3
"""Exercise persistent allocation, recovery, concurrency, and the build/publish prompt."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("allocator", ROOT / "tools/next-experimental-build.py")
allocator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(allocator)


class BuildVersions(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="folio-version-check-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.releases = self.root / "releases"

    def allocate(self, count=217):
        return allocator.allocate(self.root, count, self.releases)

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))

    def test_monotonic_builds_and_next_stable(self):
        self.assertEqual(self.allocate(), 1)
        self.assertEqual(self.allocate(), 2)
        self.assertEqual(self.allocate(218), 1)
        code = json.loads((self.root / ".tooling/experimental-version.json").read_text())["last_version_code"]
        self.assertEqual(code, 2_180_001)
        self.assertLess(code, 219 * allocator.BLOCK)
        with self.assertRaises(ValueError):
            self.allocate(217) # Older checkout must not produce a downgrade.

    def test_recover_from_published_and_unpublished_builds(self):
        self.write(self.releases / "latest.json", {"version_code": 217}) # Legacy server build.
        self.assertEqual(self.allocate(), 1)
        (self.root / ".tooling/experimental-version.json").unlink()
        self.write(self.releases / "latest.json", {"version_code": 2_170_007})
        self.write(self.root / "app/build/outputs/apk/release/output-metadata.json", {
            "applicationId": "com.folio.notes", "elements": [{"versionCode": 2_170_009}],
        })
        self.assertEqual(self.allocate(), 10)

    def test_exhausted_block_and_android_limit(self):
        self.write(self.releases / "latest.json", {"version_code": 2_179_999})
        with self.assertRaises(ValueError):
            self.allocate()
        self.assertEqual(self.allocate(218), 1)
        with self.assertRaises(ValueError):
            self.allocate(210_000)

    def test_corrupt_state_does_not_restart_counter(self):
        self.write(self.root / ".tooling/experimental-version.json", {"schema_version": 1, "last_version_code": "bad"})
        with self.assertRaises(ValueError):
            self.allocate()

    def prepare_build(self):
        (self.root / "tools").mkdir()
        # Isolate the build wrapper from the live VPS's published counter too.
        helper = (ROOT / "tools/next-experimental-build.py").read_text().replace(
            'default=Path("/var/lib/folio-releases")', f'default=Path({str(self.releases)!r})')
        (self.root / "tools/next-experimental-build.py").write_text(helper)
        (self.root / "build.sh").write_text((ROOT / "build.sh").read_text())
        (self.root / ".signing").mkdir()
        (self.root / ".signing/password").write_text("test-only")
        (self.root / "release-server").mkdir()
        (self.root / "release-server/publish.sh").write_text('#!/usr/bin/env bash\nprintf "%s" "$FOLIO_EXPECTED_VERSION_CODE" > published\n')
        (self.root / "app/build/outputs/apk/release").mkdir(parents=True)
        (self.root / "app/build/outputs/apk/release/app-release.apk").touch()
        (self.root / "gradlew").write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > gradle-args\nexit "${BUILD_TEST_EXIT:-0}"\n')
        (self.root / "gradlew").chmod(0o755)
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        (fake_bin / "git").write_text("#!/usr/bin/env bash\necho 217\n")
        (fake_bin / "git").chmod(0o755)
        return {**os.environ, "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"]}

    def test_concurrent_allocator_reservations(self):
        env = self.prepare_build()
        def reserve(_):
            result = subprocess.run([sys.executable, str(self.root / "tools/next-experimental-build.py"),
                "--release-data", str(self.releases)], env=env, capture_output=True, text=True, check=True)
            return int(result.stdout.split()[1])
        with ThreadPoolExecutor(max_workers=6) as pool:
            revisions = list(pool.map(reserve, range(12)))
        self.assertEqual(sorted(revisions), list(range(1, 13)))

    def test_prompt_and_failed_builds(self):
        env = self.prepare_build()
        marker = self.root / "published"
        for revision, (answer, published) in enumerate([("y\n", True), ("Y\n", True), ("n\n", False), ("\n", False), ("", False)], 1):
            marker.unlink(missing_ok=True)
            result = subprocess.run(["bash", str(self.root / "build.sh"), "--offline"],
                input=answer, env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(marker.exists(), published)
            if published:
                self.assertEqual(marker.read_text(), str(2170000 + revision))
            self.assertEqual((self.root / "gradle-args").read_text().splitlines(), [":app:assembleRelease", "--offline",
                f"-PfolioExperimentalBuild={revision}", "-PfolioExperimentalCommitCount=217"])
        marker.unlink(missing_ok=True)
        result = subprocess.run(["bash", str(self.root / "build.sh")], input="y\n",
            env={**env, "BUILD_TEST_EXIT": "1"}, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists())
        self.assertEqual(self.allocate(), 7) # Failed attempts never reuse a reserved number.


if __name__ == "__main__":
    unittest.main()
