#!/usr/bin/env bash
# Builds a signed experimental release APK using the keystore in .signing/,
# then (optionally) publishes it to the Folio server.
set -euo pipefail
cd "$(dirname "$0")"

# ── Options ──────────────────────────────────────────────────────────────────
publish=ask      # ask | yes | no
verbose=0
minify=false
gradle_args=()

usage() {
    cat <<'EOF'
Usage: ./build.sh [options] [-- extra gradle args]

  -p, --publish     publish to the Folio server without asking
  -n, --no-publish  build only; never prompt to publish
  -r, --r8          enable R8 minify + resource shrinking (slow; off by default)
  -v, --verbose     stream raw Gradle output instead of the progress view
  -h, --help        show this help

Environment: NO_COLOR=1 disables colour; JAVA_HOME / ANDROID_HOME override defaults.
FOLIO_BUILD_TOOLS overrides the Android build-tools directory.
Every successful build verifies the APK signature, package and reserved version.
EOF
}

while (($#)); do
    case "$1" in
        -p|--publish) publish=yes ;;
        -n|--no-publish) publish=no ;;
        -r|--r8) minify=true ;;
        -v|--verbose) verbose=1 ;;
        -h|--help) usage; exit 0 ;;
        --) shift; gradle_args+=("$@"); break ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

# ── Terminal styling ─────────────────────────────────────────────────────────
if [[ -t 1 && -z "${NO_COLOR:-}" && "${TERM:-dumb}" != dumb ]]; then
    tty=1
    B=$'\e[1m' D=$'\e[2m' R=$'\e[0m'
    RED=$'\e[31m' GRN=$'\e[32m' YLW=$'\e[33m' BLU=$'\e[34m' MAG=$'\e[35m' CYN=$'\e[36m'
else
    tty=0 B= D= R= RED= GRN= YLW= BLU= MAG= CYN=
fi
if [[ "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" == *UTF-8* || "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" == *utf8* ]]; then
    OK="✔" BAD="✘" ARROW="›" DOT="•" WARN="!" SPIN=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏) RULE="─"
else
    OK="+" BAD="x" ARROW=">" DOT="*" WARN="!" SPIN=('|' '/' '-' '\') RULE="-"
fi

cols=$( (tput cols 2>/dev/null) || echo 80 )
((cols > 78)) && cols=78
rule() { printf '%s' "$D"; printf "%${cols}s" '' | sed "s/ /$RULE/g"; printf '%s\n' "$R"; }
info() { printf '  %s%s%s %s\n' "$BLU" "$ARROW" "$R" "$*"; }
ok()   { printf '  %s%s%s %s\n' "$GRN" "$OK" "$R" "$*"; }
warn() { printf '  %s%s%s %s\n' "$YLW" "$WARN" "$R" "$*"; }
fail() { printf '  %s%s%s %s%s%s\n' "$RED" "$BAD" "$R" "$B" "$*" "$R" >&2; }
step() { printf '\n%s%s[%s/%s]%s %s%s%s\n' "$B" "$MAG" "$1" "$total_steps" "$R" "$B" "$2" "$R"; }
fmt_secs() { local s=$1; if ((s >= 60)); then printf '%dm %02ds' $((s / 60)) $((s % 60)); else printf '%ds' "$s"; fi; }
human_size() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1 B"; }

total_steps=4
[[ $publish == no ]] && total_steps=3
start_all=$SECONDS
log=
staging=
cleanup() {
    if [[ $tty == 1 ]]; then printf '\e[?25h'; fi
    if [[ -n $staging ]]; then rm -rf -- "$staging"; fi
}
trap cleanup EXIT
trap 'echo; fail "Interrupted"; exit 130' INT TERM

# ── Banner ───────────────────────────────────────────────────────────────────
printf '\n%s%s  Folio%s %sexperimental release builder%s\n' "$B" "$CYN" "$R" "$D" "$R"
rule
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')
sha=$(git rev-parse --short HEAD 2>/dev/null || echo '?')
dirty=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
printf '  %sCommit%s   %s%s%s @ %s' "$D" "$R" "$B" "$branch" "$R" "$sha"
if ((dirty > 0)); then printf '  %s(%s uncommitted change%s)%s' "$YLW" "$dirty" "$([[ $dirty == 1 ]] || echo s)" "$R"; fi
printf '\n'

# ── Environment ──────────────────────────────────────────────────────────────
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
tools="${FOLIO_BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
export ANDROID_KEYSTORE_PATH="$PWD/.signing/folio-release.p12"

step 1 "Preflight"
problems=0
check() { # check <label> <test-expression-result> <hint>
    if [[ $2 == 1 ]]; then ok "$1"; else fail "$1 ${D}($3)${R}"; problems=$((problems + 1)); fi
}
check "JDK        ${D}$JAVA_HOME${R}"          "$([[ -x $JAVA_HOME/bin/java ]] && echo 1 || echo 0)" "set JAVA_HOME to a JDK 25"
check "Android SDK ${D}$ANDROID_HOME${R}"      "$([[ -d $ANDROID_HOME ]] && echo 1 || echo 0)"       "set ANDROID_HOME"
check "Keystore   ${D}.signing/folio-release.p12${R}" "$([[ -f $ANDROID_KEYSTORE_PATH ]] && echo 1 || echo 0)" "missing keystore"
check "Password   ${D}.signing/password${R}"   "$([[ -r .signing/password ]] && echo 1 || echo 0)"   "missing password file"
check "APK verifier ${D}$tools/apksigner${R}" "$([[ -x $tools/apksigner ]] && echo 1 || echo 0)" "set FOLIO_BUILD_TOOLS"
check "APK metadata ${D}$tools/aapt${R}" "$([[ -x $tools/aapt ]] && echo 1 || echo 0)" "set FOLIO_BUILD_TOOLS"
for command in python3 flock sha256sum; do
    check "$command" "$(command -v "$command" >/dev/null && echo 1 || echo 0)" "install $command"
done
if ((problems)); then
    fail "$problems preflight problem(s); fix them and rerun."
    exit 1
fi
ANDROID_KEYSTORE_PASSWORD="$(tr -d '\r\n' < .signing/password)"
export ANDROID_KEYSTORE_PASSWORD

# Serialize the whole build/publish flow, because Gradle shares one release APK path.
mkdir -p .tooling
exec 9>.tooling/experimental-build.lock
if ! flock -n 9; then
    warn "Another experimental build is running; waiting for it to finish…"
    flock 9
fi

# ── Version reservation ──────────────────────────────────────────────────────
step 2 "Reserving version"
allocation=$(python3 tools/next-experimental-build.py 9>&-)
read -r allocated_commit experimental_build <<< "$allocation"
version_code=$((allocated_commit * 10000 + experimental_build))
stable_name="$((allocated_commit / 100)).$(((allocated_commit / 10) % 10)).$((allocated_commit % 10))"
ok "Version ${B}${stable_name}-exp.${experimental_build}${R}  ${D}(code $version_code, commit count $allocated_commit)${R}"

# ── Gradle build ─────────────────────────────────────────────────────────────
step 3 "Building signed release APK"
log=$(mktemp -t folio-build.XXXXXX.log)
gradle_cmd=(./gradlew :app:assembleRelease --console=plain
    "-PfolioExperimentalBuild=$experimental_build"
    "-PfolioExperimentalCommitCount=$allocated_commit"
    "-PfolioMinify=$minify"
    "${gradle_args[@]}")
build_start=$SECONDS

if ((verbose)); then
    set +e
    "${gradle_cmd[@]}" 9>&- 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    set -e
else
    "${gradle_cmd[@]}" >"$log" 2>&1 9>&- &
    pid=$!
    [[ $tty == 1 ]] && printf '\e[?25l'
    i=0
    while kill -0 "$pid" 2>/dev/null; do
        task=$(grep -a '^> Task ' "$log" 2>/dev/null | tail -1 | sed 's/^> Task //; s/ [A-Z-]*$//' || true)
        done_count=$(grep -ac '^> Task ' "$log" 2>/dev/null || true)
        elapsed=$((SECONDS - build_start))
        line=$(printf '  %s%s%s %s %s· %s tasks · %s%s' "$CYN" "${SPIN[i % ${#SPIN[@]}]}" "$R" \
            "$(fmt_secs "$elapsed")" "$D" "$done_count" "${task:-starting Gradle daemon…}" "$R")
        if [[ $tty == 1 ]]; then
            # Trim to the terminal width (ignoring colour codes) and redraw in place.
            plain=$(printf '%s' "$line" | sed $'s/\e\\[[0-9;]*m//g')
            if ((${#plain} > cols)); then line="${line:0:$((${#line} - (${#plain} - cols) - 1))}…${R}"; fi
            printf '\r\e[2K%s' "$line"
        fi
        i=$((i + 1))
        sleep 0.1
    done
    set +e; wait "$pid"; status=$?; set -e
    [[ $tty == 1 ]] && printf '\r\e[2K\e[?25h'
fi

build_secs=$((SECONDS - build_start))
if ((status != 0)); then
    fail "Gradle failed after $(fmt_secs "$build_secs") (exit $status)"
    if ((!verbose)); then
        echo
        grep -aE '^(e: |FAILURE|\* What went wrong|Execution failed|> )' "$log" | grep -avE '^> Task ' | head -15 | sed "s/^/    ${RED}|${R} /" || true
        echo
        info "Full log: ${B}$log${R}"
    fi
    exit "$status"
fi

executed=$(grep -ac '^> Task ' "$log" || true)
cached=$(grep -aEc '^> Task .* (FROM-CACHE|UP-TO-DATE)$' "$log" || true)
ran=$((executed - cached))
cache_note=
grep -aq 'Configuration cache entry reused' "$log" && cache_note=" ${D}· config cache hit${R}"
ok "Build finished in ${B}$(fmt_secs "$build_secs")${R} ${D}· $ran ran, $cached reused${R}${cache_note}"
rm -f "$log"

# ── Verify & summarize ───────────────────────────────────────────────────────
apk=app/build/outputs/apk/release/app-release.apk
if [[ ! -f $apk || -L $apk ]]; then fail "No regular release APK found at $apk"; exit 1; fi
# Verify, summarize and publish one private snapshot even if another build replaces
# Gradle's output while the publication prompt is open.
staging=$(mktemp -d -t folio-build.XXXXXX)
verified_apk="$staging/app-release.apk"
cp -- "$apk" "$verified_apk"
size=$(stat -c %s "$verified_apk" 2>/dev/null || stat -f %z "$verified_apk")
if ((size == 0 || size > 100 * 1024 * 1024)); then
    fail "Release APK must be between 1 byte and 100 MiB"; exit 1
fi
sum=$(sha256sum -- "$verified_apk")
sum=${sum%% *}
if ! signers=$("$tools/apksigner" verify --print-certs "$verified_apk"); then
    fail "Release APK signature verification failed"; exit 1
fi
cert=$(printf '%s\n' "$signers" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
if [[ ! $cert =~ ^[0-9a-f]{64}$ ]]; then
    fail "Release APK has no valid signing certificate fingerprint"; exit 1
fi
if ! badging=$("$tools/aapt" dump badging "$verified_apk"); then
    fail "Could not read release APK metadata"; exit 1
fi
package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
actual_code=$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p")
actual_version=$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")
expected_version="${stable_name}-exp.${experimental_build}"
if [[ $package != com.folio.notes || $actual_code != "$version_code" || $actual_version != "$expected_version" ]]; then
    fail "Release APK package/version does not match the reserved build ($expected_version, code $version_code)"; exit 1
fi

echo
rule
printf '  %sAPK%s        %s%s%s\n' "$D" "$R" "$B" "$apk" "$R"
printf '  %sVersion%s    %s%s-exp.%s%s  %s(code %s)%s\n' "$D" "$R" "$GRN$B" "$stable_name" "$experimental_build" "$R" "$D" "$version_code" "$R"
printf '  %sSize%s       %s\n' "$D" "$R" "$(human_size "$size")"
printf '  %sSHA-256%s    %s%s…%s\n' "$D" "$R" "$D" "${sum:0:32}" "$R"
if [[ -n $cert ]]; then
    printf '  %sSigner%s     %s…  %s%s verified%s\n' "$D" "$R" "${cert:0:16}" "$GRN" "$OK" "$R"
fi
rule

# ── Publish ──────────────────────────────────────────────────────────────────
do_publish=0
case "$publish" in
    yes) do_publish=1 ;;
    no) ;;
    ask)
        if [[ -t 0 ]]; then
            step 4 "Publish"
            printf '  %sPublish this build to the Folio server?%s [y/N] ' "$B" "$R"
            if read -r answer; then
                case "$answer" in [Yy]|[Yy][Ee][Ss]) do_publish=1 ;; esac
            fi
        else
            # A build without interactive input must not publish automatically.
            info "Non-interactive session; not publishing ${D}(use --publish to force)${R}"
        fi
        ;;
esac

if [[ $publish == yes ]]; then step 4 "Publish"; fi
if ((do_publish)); then
    pub_start=$SECONDS
    if FOLIO_EXPECTED_VERSION_CODE=$version_code FOLIO_EXPECTED_VERSION_NAME=$expected_version \
        FOLIO_EXPECTED_APK_SHA256=$sum FOLIO_BUILD_TOOLS=$tools \
        bash ./release-server/publish.sh "$verified_apk" 9>&-; then
        ok "Published ${B}${stable_name}-exp.${experimental_build}${R} in $(fmt_secs $((SECONDS - pub_start)))"
    else
        fail "Publish failed; the APK is still at $apk"
        exit 1
    fi
else
    info "Not published ${D}(run ./release-server/publish.sh to publish later)${R}"
fi

printf '\n  %s%s Done%s in %s\n\n' "$GRN$B" "$OK" "$R" "$(fmt_secs $((SECONDS - start_all)))"
