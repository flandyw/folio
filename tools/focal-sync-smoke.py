#!/usr/bin/env python3
"""Exercise the actual RPC/auth boundary against a loopback server, using cached SDK jars."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
kotlin = re.search(r'plugin.compose"\) version "([^"]+)"', (root / "build.gradle.kts").read_text())[1]
sdk = re.search(r'supabase:bom:([^"]+)', (root / "app/build.gradle.kts").read_text())[1]
java_home = os.environ.get("JAVA_HOME")
java = str(Path(java_home) / "bin/java") if java_home else "java"


def cached(group, module, version, suffix=".jar"):
    files = list((cache / group / module / version).rglob(f"*{suffix}"))
    if not files:
        raise RuntimeError(f"Missing cached {module} {version}; run ./build.sh -p first")
    return next(p for p in files if not p.name.endswith(("-sources.jar", "-javadoc.jar")))


compiler = cached("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin)
pom = ET.parse(cached("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin, ".pom"))
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
compiler_cp = [compiler]
for dependency in pom.findall("m:dependencies/m:dependency", ns):
    compiler_cp.append(cached(*(dependency.find(f"m:{name}", ns).text
                                for name in ("groupId", "artifactId", "version"))))
compiler_cp.append(cached("org.jetbrains", "annotations", "13.0"))

with tempfile.TemporaryDirectory(prefix="folio-sync-") as work:
    temp = Path(work)
    runtime = [cached("org.jetbrains.kotlin", "kotlin-stdlib", kotlin),
               cached("org.json", "json", "20240303")]
    groups = ["io.github.jan-tennert.supabase", "io.ktor", "org.jetbrains.kotlinx",
              "com.squareup.okhttp3", "com.squareup.okio", "org.slf4j", "co.touchlab", "com.russhwolf"]
    for group in groups:
        for module in (cache / group).iterdir():
            if module.name.endswith("-debug"):
                continue
            if group == "io.github.jan-tennert.supabase" and module.name not in (
                "auth-kt-android", "postgrest-kt-android", "supabase-kt-android"
            ):
                continue
            if group == "org.jetbrains.kotlinx" and module.name not in (
                "kotlinx-coroutines-core-jvm", "kotlinx-serialization-core-jvm",
                "kotlinx-serialization-json-jvm", "kotlinx-datetime-jvm", "atomicfu-jvm",
                "kotlinx-io-core-jvm", "kotlinx-io-bytestring-jvm"
            ):
                continue
            versions = sorted(module.iterdir(), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
            version = sdk if group == "io.github.jan-tennert.supabase" else versions[-1].name
            for artifact in (module / version).rglob("*"):
                if artifact.suffix == ".aar":
                    target = temp / f"{module.name}.jar"
                    with zipfile.ZipFile(artifact) as archive:
                        target.write_bytes(archive.read("classes.jar"))
                    runtime.append(target)
                elif artifact.suffix == ".jar" and not artifact.name.endswith(("-sources.jar", "-javadoc.jar")):
                    runtime.append(artifact)
    runtime_cp = os.pathsep.join(map(str, runtime))
    sources = root / "app/src/main/java/com/folio/notes"
    subprocess.run([java, "-cp", os.pathsep.join(map(str, compiler_cp)),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-classpath", runtime_cp, "-d", str(temp / "classes"),
                    str(sources / "mistakes/MistakeModels.kt"), str(sources / "sync/SyncProtocol.kt"),
                    str(sources / "sync/SyncRemote.kt"), str(sources / "mistakes/ExamTrackSyncError.kt"),
                    str(root / "tools/FocalSyncSmoke.kt")], check=True)
    subprocess.run([java, "--add-modules", "jdk.httpserver", "-cp",
                    os.pathsep.join([str(temp / "classes"), runtime_cp]),
                    "com.folio.notes.sync.FocalSyncSmokeKt"], check=True, timeout=45)
