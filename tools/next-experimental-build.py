#!/usr/bin/env python3
"""Reserve an experimental version before building; failed builds may leave gaps."""

import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

BLOCK = 10_000
MAX_CODE = 2_100_000_000


def positive_code(value, source):
    if type(value) is not int or not 0 < value <= MAX_CODE:
        raise ValueError(f"Invalid version code in {source}")
    return value


def read_json(path):
    try:
        value = json.loads(path.read_text())
        if not isinstance(value, dict):
            raise ValueError(f"Invalid JSON object in {path}")
        return value
    except FileNotFoundError:
        return None


def allocate(project, commit_count, release_data):
    if not 1 <= commit_count <= MAX_CODE // BLOCK:
        raise ValueError("Git commit count is outside Android's version-code range")
    tooling = project / ".tooling"
    tooling.mkdir(parents=True, exist_ok=True)
    state_path = tooling / "experimental-version.json"
    # Also lock the allocator so it remains safe when called outside build.sh.
    with (tooling / "experimental-version.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        floor = commit_count * BLOCK
        state = read_json(state_path)
        if state is not None:
            if state.get("schema_version") != 1:
                raise ValueError(f"Invalid experimental build state: {state_path}")
            floor = max(floor, positive_code(state.get("last_version_code"), state_path))

        # Recover from missing local state using published or last successfully built APKs.
        published_path = release_data / "latest.json"
        published = read_json(published_path)
        if published is not None:
            floor = max(floor, positive_code(published.get("version_code"), published_path))
        apk_meta_path = project / "app/build/outputs/apk/release/output-metadata.json"
        apk_meta = read_json(apk_meta_path)
        if apk_meta is not None:
            if apk_meta.get("applicationId") != "com.folio.notes" or not apk_meta.get("elements"):
                raise ValueError(f"Invalid Folio APK metadata: {apk_meta_path}")
            for item in apk_meta["elements"]:
                floor = max(floor, positive_code(item.get("versionCode"), apk_meta_path))

        code = floor + 1
        revision = code - commit_count * BLOCK
        if code > MAX_CODE or revision > BLOCK - 1:
            raise ValueError("No experimental numbers remain for this commit count, or this checkout is older than your last build. Advance to a newer commit before building.")
        state = {"schema_version": 1, "last_version_code": code}
        descriptor, temporary = tempfile.mkstemp(prefix=".experimental-version-", dir=tooling)
        try:
            with os.fdopen(descriptor, "w") as out:
                json.dump(state, out)
                out.write("\n")
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, state_path)
            directory = os.open(tooling, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return revision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--release-data", type=Path, default=Path("/var/lib/folio-releases"))
    args = parser.parse_args()
    count = int(subprocess.check_output(["git", "-C", str(args.project), "rev-list", "--count", "HEAD"], text=True).strip())
    revision = allocate(args.project, count, args.release_data)
    print(f"{count} {revision}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Cannot reserve experimental build: {error}", file=sys.stderr)
        sys.exit(1)
