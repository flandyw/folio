# Experimental update server

Folio's opt-in experimental channel is served from <https://folio.flandolf.me/releases/>
by the generic [`release-server`](https://github.com/flandyw/release-server) (a git
submodule at `release-server/`; run `git submodule update --init` after cloning).
Stable updates stay on GitHub Releases. Server setup, configuration keys, endpoints and
hardening are documented in that repo's README. Folio's settings live in
[`release-server.conf`](../release-server.conf).

In Folio, **Settings → Account & updates → Enable experimental builds**, then **Check
Folio server**. The toggle defaults to off and persists. Each source has its own check
timestamps and retry cooldowns. Switching off returns to GitHub; Android installs the
next stable release whose version code exceeds the installed experimental build.

## Setup / publish

```sh
./release-server/install.sh you@example.com        # reads ./release-server.conf
./release-server/pin-cert.sh official-stable.apk   # pin the stable release certificate
bash ./build.sh -p                                 # build + publish (or ./release-server/publish.sh apk)
```

`build.sh` hands `publish.sh` the reserved version code/name and the verified APK's
SHA-256 as `RELEASE_EXPECTED_*`, plus `RELEASE_BUILD_TOOLS`. `publish.sh` re-checks them,
the `com.folio.notes` package and the pinned signing certificate. Use the **same
signing key as the stable GitHub APK**.

## Folio versioning

Folio sets `VERSION_SCHEME=folio`. Stable version codes are `commitCount * 10000`; every
`build.sh` run reserves a build number 1..9999 and adds it to that base, displayed as
`X.Y.Z-exp.N`. Commit count 217 gives stable code 2,170,000 and experimental codes
2,170,001, 2,170,002, …; the next stable commit (2,180,000) supersedes them all. The
high-water code is saved in ignored `.tooling/experimental-version.json`, and the
allocator also reads the server's latest manifest and the last built APK's
`output-metadata.json`, so deleting its state never reuses a delivered code. Failed
attempts leave harmless gaps. Python 3 and `flock` are required. Check with
`python3 tools/build-version-smoke.py`.

GitHub releases keep their `vX.Y.Z` tags and ship `update.json` with the APK's real
version code/name/tag; the updater reads it before comparing versions (older releases
without it use the legacy tag rules).

Verify the app side with `node tools/update-smoke.cjs [server-manifest.json]`; on a
device, check the toggle persists, each check uses its source, and a newer signed
experimental build installs over stable without removing notebooks.
