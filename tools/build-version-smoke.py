#!/usr/bin/env python3
"""Exercise persistent allocation, recovery, concurrency, and the build/publish prompt."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import hashlib
import shutil
import json
import os
import pty
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
        # Isolate all build tools, credentials, and release storage from the VPS.
        helper = (ROOT / "tools/next-experimental-build.py").read_text().replace(
            'default=Path("/var/lib/folio-releases")', f'default=Path({str(self.releases)!r})')
        (self.root / "tools/next-experimental-build.py").write_text(helper)
        (self.root / "build.sh").write_text((ROOT / "build.sh").read_text())
        (self.root / ".signing").mkdir()
        (self.root / ".signing/password").write_text("test-only")
        (self.root / ".signing/folio-release.p12").touch()
        (self.root / "release-server").mkdir()
        (self.root / "release-server/publish.sh").write_text('''#!/usr/bin/env bash
set -eu
python3 - "$1" <<'CHECK'
import hashlib, json, os, pathlib, sys
apk = pathlib.Path(sys.argv[1])
assert hashlib.sha256(apk.read_bytes()).hexdigest() == os.environ["RELEASE_EXPECTED_APK_SHA256"]
pathlib.Path("published").write_text(json.dumps({"path": str(apk),
    "code": os.environ["RELEASE_EXPECTED_VERSION_CODE"], "name": os.environ["RELEASE_EXPECTED_VERSION_NAME"]}))
CHECK
''')
        (self.root / "app/build/outputs/apk/release").mkdir(parents=True)
        (self.root / "gradlew").write_text('''#!/usr/bin/env bash
printf "%s\\n" "$@" > gradle-args
if [[ ${BUILD_TEST_EXIT:-0} != 0 ]]; then exit "$BUILD_TEST_EXIT"; fi
for arg in "$@"; do
    case "$arg" in
        -PfolioExperimentalBuild=*) revision=${arg#*=} ;;
        -PfolioExperimentalCommitCount=*) count=${arg#*=} ;;
    esac
done
printf "package: name='${BUILD_TEST_PACKAGE:-com.folio.notes}' versionCode='%s' versionName='%s'\\n" \
    "$((count * 10000 + revision))" "${BUILD_TEST_VERSION:-$((count / 100)).$(((count / 10) % 10)).$((count % 10))-exp.$revision}" \
    > app/build/outputs/apk/release/app-release.apk
''')
        (self.root / "gradlew").chmod(0o755)
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        (fake_bin / "git").write_text("#!/usr/bin/env bash\nif [[ $1 != status ]]; then echo 217; fi\n")
        (fake_bin / "git").chmod(0o755)
        jdk = self.root / "jdk"
        (jdk / "bin").mkdir(parents=True)
        (jdk / "bin/java").write_text("#!/usr/bin/env bash\nexit 0\n")
        (jdk / "bin/java").chmod(0o755)
        sdk = self.root / "sdk"
        build_tools = sdk / "build-tools/36.0.0"
        build_tools.mkdir(parents=True)
        (build_tools / "aapt").write_text('#!/usr/bin/env bash\ncat "$3"\n')
        (build_tools / "apksigner").write_text('#!/usr/bin/env bash\nif [[ ${BUILD_TEST_BAD_SIGNATURE:-0} == 1 ]]; then exit 1; fi\nprintf "Signer #1 certificate SHA-256 digest: %s\\n" "' + "a"*64 + '"\n')
        for tool in (build_tools / "aapt", build_tools / "apksigner"):
            tool.chmod(0o755)
        return {**os.environ, "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
                "JAVA_HOME": str(jdk), "ANDROID_HOME": str(sdk), "FOLIO_BUILD_TOOLS": str(build_tools)}

    def run_build(self, env, *args, answer=None):
        command = ["bash", str(self.root / "build.sh"), "--verbose", *args]
        if answer is None:
            return subprocess.run(command, stdin=subprocess.DEVNULL, env=env, text=True, capture_output=True)
        master, slave = pty.openpty()
        try:
            os.write(master, answer.encode() if answer else b"\x04")
            return subprocess.run(command, stdin=slave, env=env, text=True, capture_output=True, timeout=15)
        finally:
            os.close(master)
            os.close(slave)

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
            result = self.run_build(env, "--", "--offline", answer=answer)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(marker.exists(), published)
            if published:
                record = json.loads(marker.read_text())
                self.assertEqual(record["code"], str(2170000 + revision))
                self.assertEqual(record["name"], f"2.1.7-exp.{revision}")
                # The verified private snapshot was published, then cleaned up.
                self.assertNotEqual(record["path"], "app/build/outputs/apk/release/app-release.apk")
                self.assertFalse(Path(record["path"]).exists())
            self.assertEqual((self.root / "gradle-args").read_text().splitlines(), [":app:assembleRelease", "--console=plain",
                f"-PfolioExperimentalBuild={revision}", "-PfolioExperimentalCommitCount=217", "--offline"])
        marker.unlink(missing_ok=True)
        result = self.run_build({**env, "BUILD_TEST_EXIT": "1"}, "--publish")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists())
        self.assertEqual(self.allocate(), 7) # Failed attempts never reuse a reserved number.

    def test_explicit_publish_flags_and_noninteractive_default(self):
        env = self.prepare_build()
        marker = self.root / "published"
        for args, expected in [((), False), (("--no-publish",), False), (("--publish",), True)]:
            marker.unlink(missing_ok=True)
            result = self.run_build(env, *args)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(marker.exists(), expected)

    def test_invalid_apk_never_publishes(self):
        env = self.prepare_build()
        for overrides in [{"BUILD_TEST_BAD_SIGNATURE": "1"}, {"BUILD_TEST_PACKAGE": "evil.package"},
                          {"BUILD_TEST_VERSION": "2.1.7-exp.999"}]:
            result = self.run_build({**env, **overrides}, "--publish")
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse((self.root / "published").exists())

    def test_publish_wrapper_rechecks_expected_artifact(self):
        env = self.prepare_build()
        # The real wrapper and its config loader, with the pin directory redirected.
        shutil.copytree(ROOT / "release-server/deploy", self.root / "release-server/deploy", dirs_exist_ok=True)
        script = self.root / "release-server/publish.sh"
        shutil.copy(ROOT / "release-server/publish.sh", script)
        pin_dir = self.root / "pin"
        pin_dir.mkdir()
        pin = pin_dir / "signing-cert.sha256"
        pin.write_text("a" * 64 + "\n")
        (self.root / "release-server.conf").write_text(
            (ROOT / "release-server.conf").read_text() + f"CONFIG_DIR={pin_dir}\n")
        # Record the sudo invocation without executing anything privileged, and
        # keep the final public health request off the network.
        fake_bin = self.root / "fake-bin"
        (fake_bin / "sudo").write_text('''#!/usr/bin/env bash
python3 - "$@" <<'CHECK'
import json, pathlib, sys
pathlib.Path("published-command").write_text(json.dumps(sys.argv[1:]))
CHECK
''')
        (fake_bin / "curl").write_text("#!/usr/bin/env bash\nexit 0\n")
        for tool in (fake_bin / "sudo", fake_bin / "curl"):
            tool.chmod(0o755)
        apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        apk.write_text("package: name='com.folio.notes' versionCode='2170001' versionName='2.1.7-exp.1'\n")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        env.update({"RELEASE_EXPECTED_VERSION_CODE": "2170001", "RELEASE_EXPECTED_VERSION_NAME": "2.1.7-exp.1",
                    "RELEASE_EXPECTED_APK_SHA256": digest})
        marker = self.root / "published-command"
        for overrides in [{"RELEASE_EXPECTED_APK_SHA256": "0" * 64}, {"RELEASE_EXPECTED_VERSION_CODE": "2170002"},
                          {"RELEASE_EXPECTED_VERSION_NAME": "2.1.7-exp.2"}]:
            result = subprocess.run(["bash", str(script), str(apk)], env={**env, **overrides}, cwd=self.root,
                                    text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(marker.exists())
        result = subprocess.run(["bash", str(script), str(apk)], env=env, cwd=self.root, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        invocation = json.loads(marker.read_text())
        command = invocation.index("/usr/local/bin/folio-release-server")
        self.assertEqual(invocation[command + 1], "publish")
        self.assertIn("RELEASE_SLUG=folio", invocation)
        self.assertEqual(invocation[command + 4:], ["-version-name", "2.1.7-exp.1", "-version-code", "2170001",
                                        "-expected-sha256", digest])
        self.assertFalse(Path(invocation[command + 3]).exists()) # Wrapper cleaned up its snapshot.

    def test_missing_verifier_fails_before_reserving_version(self):
        env = self.prepare_build()
        Path(env["FOLIO_BUILD_TOOLS"], "apksigner").unlink()
        result = self.run_build(env, "--publish")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / ".tooling/experimental-version.json").exists())


if __name__ == "__main__":
    unittest.main()
