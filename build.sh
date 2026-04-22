#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
#  RemoteAccess — Build script
#  Produces:
#    apk-output/RemoteAccess-debug.apk   (debug, unobfuscated)
#    apk-output/RemoteAccess-release.apk (release, signed + R8 + ProGuard)
#
#  Usage:
#    bash build.sh           — incremental build (fast, skips unchanged tasks)
#    bash build.sh --clean   — full clean build from scratch
# ─────────────────────────────────────────────────────────────────────────────

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_SDK_DIR="/tmp/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"
ZULU_JDK="/nix/store/0zjj9k6wz5hl4jizcfrkr0i4l8q45v51-zulu-ca-jdk-17.0.8.1"
KEYSTORE="$ROOT_DIR/app/release.keystore"
KEY_ALIAS="tusker_key"
KEY_PASS="K#9mXq@Lp2!ZrVt&NwYsBjC5uEhGfD8"
STORE_PASS="K#9mXq@Lp2!ZrVt&NwYsBjC5uEhGfD8"

CLEAN_BUILD=0
for arg in "$@"; do
  [ "$arg" = "--clean" ] && CLEAN_BUILD=1
done

# ── 0. Clean (only when --clean is passed) ───────────────────────────────────
if [ "$CLEAN_BUILD" -eq 1 ]; then
  echo "==> Cleaning previous build artifacts..."
  rm -f "$ROOT_DIR"/apk-output/*.apk
  rm -rf "$ROOT_DIR/app/build"
  echo "  Cleaned."
else
  echo "==> Incremental build (pass --clean to do a full clean build)"
fi

# ── 1. Java ───────────────────────────────────────────────────────────────────
echo ""
echo "==> Configuring Java..."
if [ -d "$ZULU_JDK" ]; then
    export JAVA_HOME="$ZULU_JDK"
    echo "  Using Zulu JDK 17 at $JAVA_HOME"
else
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    echo "  Using system Java at $JAVA_HOME"
fi
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | sed 's/^/    /'

# ── 2. Android SDK command-line tools ─────────────────────────────────────────
echo ""
echo "==> Setting up Android SDK..."
if [ ! -f "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "  Downloading command-line tools..."
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"
    mkdir -p /tmp/android-sdk-temp
    cd /tmp/android-sdk-temp
    jar xf "$CMDLINE_TOOLS_ZIP"
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
    mv /tmp/android-sdk-temp/cmdline-tools "$ANDROID_SDK_DIR/cmdline-tools/latest"
    cd "$ROOT_DIR"
    chmod +x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    echo "  Command-line tools ready."
else
    echo "  Command-line tools already present."
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# ── 3. SDK licenses ───────────────────────────────────────────────────────────
echo ""
echo "==> Accepting SDK licenses..."
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1 || true

# ── 4. SDK platform & build-tools ─────────────────────────────────────────────
echo ""
echo "==> Installing SDK components..."
MISSING=0
[ ! -d "$ANDROID_SDK_DIR/platforms/android-36" ] && MISSING=1
[ ! -d "$ANDROID_SDK_DIR/build-tools/35.0.0"  ] && MISSING=1
if [ "$MISSING" -eq 1 ]; then
    sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0"
    echo "  Installed: platforms;android-36 + build-tools;35.0.0"
else
    echo "  Already installed: platforms;android-36 + build-tools;35.0.0"
fi

# ── 5. Release keystore ───────────────────────────────────────────────────────
echo ""
echo "==> Checking release keystore..."
if [ ! -f "$KEYSTORE" ]; then
    echo "  Generating new release keystore..."
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=RemoteAccess, OU=Mobile, O=Corp, L=City, ST=State, C=US" \
        -sigalg SHA256withRSA \
        2>&1 | sed 's/^/    /'
    echo "  Keystore created: $KEYSTORE"
else
    echo "  Keystore present: $KEYSTORE"
fi

# ── 6. Obfuscation dictionary ─────────────────────────────────────────────────
echo ""
echo "==> Generating obfuscation dictionary..."
python3 - << 'PYEOF'
import random, os

random.seed(0xDEADBEEF)
chars = ['I', 'l', '1', 'O', '0', 'Il', 'lI', '1l', 'l1', 'II', 'll', '00', 'O0']
extra = [''.join(random.choices('IlO01', k=random.randint(3, 8))) for _ in range(2000)]
words = list({w for w in extra if not w.isdigit()})
random.shuffle(words)
out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'app', 'obf-dict.txt')
with open(out_path, 'w') as f:
    f.write('\n'.join(words[:1500]))
print(f"  Written {min(len(words), 1500)} entries to app/obf-dict.txt")
PYEOF

# ── 7. Project config files ───────────────────────────────────────────────────
echo ""
echo "==> Writing project config..."

cat > "$ROOT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_SDK_DIR
EOF

# Stable Gradle + R8 settings:
#   - daemon=false         : avoids stale daemon state across builds
#   - parallel=false       : single-threaded is more predictable in CI
#   - configureondemand=false : full configuration, avoids partial-config surprises
#   - Xmx2g               : enough for R8 full-mode without OOM; 3 g sometimes triggers GC thrash
#   - R8 full mode         : maximum shrinking/obfuscation, set here so it applies globally
cat > "$ROOT_DIR/gradle.properties" <<EOF
android.useAndroidX=true
android.enableJetifier=true
android.suppressUnsupportedCompileSdk=36
android.enableR8.fullMode=true
org.gradle.jvmargs=-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.configureondemand=false
EOF

echo "  local.properties  — sdk.dir=$ANDROID_SDK_DIR"
echo "  gradle.properties — AndroidX + R8 full mode + stable JVM flags"

# ── 8. Gradle wrapper JAR ─────────────────────────────────────────────────────
echo ""
echo "==> Checking Gradle wrapper..."
WRAPPER_JAR="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "  Downloading gradle-wrapper.jar..."
    mkdir -p "$ROOT_DIR/gradle/wrapper"
    curl -fsSL "https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar" \
        -o "$WRAPPER_JAR"
    echo "  Downloaded."
else
    echo "  Already present."
fi
chmod +x "$ROOT_DIR/gradlew"

# ── 9. Build both APKs in a single Gradle invocation ─────────────────────────
# Running assembleDebug and assembleRelease together lets Gradle share dependency
# resolution, resource merging, and manifest processing across both variants —
# significantly faster than two separate ./gradlew calls.
echo ""
echo "==> Building DEBUG + RELEASE APKs..."
cd "$ROOT_DIR"
./gradlew assembleDebug assembleRelease \
    --no-daemon \
    --stacktrace \
    2>&1

# ── 10. Collect outputs ───────────────────────────────────────────────────────
mkdir -p "$ROOT_DIR/apk-output"

DEBUG_SRC="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$DEBUG_SRC" ]; then
    cp "$DEBUG_SRC" "$ROOT_DIR/apk-output/RemoteAccess-debug.apk"
    DEBUG_SIZE=$(ls -lh "$ROOT_DIR/apk-output/RemoteAccess-debug.apk" | awk '{print $5}')
    echo ""
    echo "  Debug APK:   apk-output/RemoteAccess-debug.apk ($DEBUG_SIZE)"
else
    echo "  WARNING: Debug APK not found — check build output above"
fi

RELEASE_SRC="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$RELEASE_SRC" ]; then
    RELEASE_SRC=$(find "$ROOT_DIR/app/build/outputs/apk/release" -name "*.apk" 2>/dev/null | head -1)
fi
if [ -n "$RELEASE_SRC" ] && [ -f "$RELEASE_SRC" ]; then
    cp "$RELEASE_SRC" "$ROOT_DIR/apk-output/RemoteAccess-release.apk"
    RELEASE_SIZE=$(ls -lh "$ROOT_DIR/apk-output/RemoteAccess-release.apk" | awk '{print $5}')
    echo "  Release APK: apk-output/RemoteAccess-release.apk ($RELEASE_SIZE)"
    echo ""
    echo "  Protection applied:"
    echo "    R8 full mode     — maximum class/method shrinking + inlining"
    echo "    ProGuard rules   — 5-pass optimisation, log stripping, repackaging"
    echo "    Obf. dictionary  — look-alike identifiers (I/l/O/0)"
    echo "    shrinkResources  — unused resources stripped"
    echo "    RSA-4096 keystore — release signing"
else
    echo "  WARNING: Release APK not found — check build output above"
fi

# ── 11. Anti-decompile / anti-baksmali hardening (release APK only) ──────────
# Goal: make `apktool d` / `baksmali` / common APK reversers fail or produce
# garbage, while the APK still installs and runs cleanly on Android.
#
# Techniques applied (all post-build, no source changes):
#   a) zipalign -p -f -v 4              — page-align native libs (required by
#                                          apksigner v2+ and improves load).
#   b) Strip v1 (JAR) signature, sign  — apktool relies heavily on META-INF/
#      with v2 + v3 + v4 only.            *.SF/*.RSA; removing them breaks many
#                                          older reversers and signature mods.
#   c) Inject "poison" ZIP entries     — extra entries with names/headers that
#      after signing.                     confuse apktool's resource and
#                                          manifest parsers (Android ignores
#                                          unknown top-level entries).
#   d) Strip debug/source attributes   — already done by R8 full mode, double-
#                                          checked here.
echo ""
echo "==> Applying anti-decompile / anti-baksmali hardening..."
RELEASE_APK="$ROOT_DIR/apk-output/RemoteAccess-release.apk"
if [ -f "$RELEASE_APK" ]; then
    BUILD_TOOLS_DIR="$ANDROID_SDK_DIR/build-tools/35.0.0"
    ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
    APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

    # (a) zipalign
    echo "  [a] zipalign -p -f 4 ..."
    ALIGNED="$ROOT_DIR/apk-output/.aligned.apk"
    "$ZIPALIGN" -p -f -v 4 "$RELEASE_APK" "$ALIGNED" > /dev/null
    mv "$ALIGNED" "$RELEASE_APK"

    # (b) Re-sign with v2 + v3 + v4 only (no v1 / JAR signature)
    echo "  [b] Stripping v1 signature, re-signing with v2 + v3 + v4 only ..."
    # Remove META-INF/*.SF *.RSA *.DSA MANIFEST.MF inserted by previous signer
    python3 - << PYEOF
import zipfile, shutil, os
src = "$RELEASE_APK"
tmp = src + ".tmp"
strip_prefixes = ("META-INF/MANIFEST.MF",)
strip_suffixes = (".SF", ".RSA", ".DSA", ".EC")
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        n = item.filename
        if n.startswith("META-INF/") and (n.endswith(strip_suffixes) or n in strip_prefixes):
            continue
        zout.writestr(item, zin.read(n))
shutil.move(tmp, src)
print("    META-INF v1 signature artefacts stripped.")
PYEOF

    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$STORE_PASS" \
        --key-pass "pass:$KEY_PASS" \
        --v1-signing-enabled false \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled true \
        "$RELEASE_APK" 2>&1 | sed 's/^/    /'

    # (c) Inject "poison" ZIP entries that break apktool/baksmali parsers but
    #     are silently ignored by Android's PackageParser.
    #     - A fake `classes0.dex` with a deliberately corrupted DEX magic header
    #       (Android only loads classes.dex, classes2.dex, …; classesN starting
    #        from a non-numeric suffix is ignored, but apktool may attempt it).
    #     - A fake resources.arsc.bak with random bytes (apktool sometimes
    #       follows backup names).
    #     - A directory entry with a UTF-8 BOM in the name to trip ZIP parsers.
    #     NOTE: must be added AFTER signing so they don't break signature
    #     verification — Android v2/v3 signatures only cover entries listed in
    #     the APK Signing Block, and the verifier ignores unknown top-level
    #     entries that aren't referenced by the manifest.
    #     We add them OUTSIDE the signed entries via apksigner-friendly method:
    #     append as STORED entries to the ZIP central directory after signing.
    echo "  [c] Injecting poison ZIP + resource entries to confuse decompilers ..."
    python3 - << PYEOF
import zipfile, os, random, struct
random.seed(0xC0FFEE)
src = "$RELEASE_APK"

# ── Crafted AXML (Android Binary XML) header that LOOKS valid (so apktool/
#    AXmlPrinter dives into it) but has a corrupted string-pool chunk that
#    triggers an IndexOutOfBounds / NegativeArraySize during decoding.
#    Android never loads these files (they aren't referenced from
#    resources.arsc), so runtime is unaffected.
def poison_axml():
    # AXML magic: 0x00080003, file size placeholder, then a malformed
    # ResStringPool_header chunk (type=0x0001) with absurd stringCount.
    header = struct.pack("<HHI", 0x0003, 0x0008, 0x10000000)  # huge "file size"
    spool  = struct.pack("<HHIIIIII",
                         0x0001, 0x001C,        # type, headerSize
                         0x10000000,            # chunk size (huge → overflow)
                         0x7FFFFFFF,            # stringCount (max int → OOM)
                         0,                     # styleCount
                         0,                     # flags
                         0x10000000,            # stringsStart (out of bounds)
                         0)                     # stylesStart
    return header + spool + bytes(random.getrandbits(8) for _ in range(512))

# ── Crafted resources.arsc-style header: looks like a ResTable but the
#    package chunk count is bogus, so aapt2/apktool's arsc parser explodes.
def poison_arsc():
    # ResTable_header: type=0x0002, headerSize=0x000C, size, packageCount
    return struct.pack("<HHII",
                       0x0002, 0x000C,
                       0x10000000,            # huge "file size"
                       0x7FFFFFFF) + bytes(random.getrandbits(8) for _ in range(2048))

poison = {
    # Generic ZIP-level decoys (already present, slightly expanded)
    "classes0.dex":            b"dex\n000\x00" + bytes(random.getrandbits(8) for _ in range(2048)),
    "AndroidManifest.xml.bak": poison_axml(),
    "resources.arsc.bak":      poison_arsc(),
    "META-INF/services/\xef\xbb\xbfpoison".encode("latin1").decode("latin1"):
        b"# decoy\n",

    # ── Resource-tree poisoning: apktool/aapt2 walk every entry under res/
    #    and try to decode AXML / arsc by extension. Each of these will abort
    #    decoding with a fatal parser error.
    "res/xml/_decoy0.xml":     poison_axml(),
    "res/xml/_decoy1.xml":     poison_axml(),
    "res/layout/_decoy0.xml":  poison_axml(),
    "res/layout/_decoy1.xml":  poison_axml(),
    "res/menu/_decoy.xml":     poison_axml(),
    "res/anim/_decoy.xml":     poison_axml(),
    "res/drawable/_decoy.xml": poison_axml(),
    "res/values/_decoy.xml":   poison_axml(),
    "res/raw/_decoy.bin":      bytes(random.getrandbits(8) for _ in range(1024)),
    "res/raw/_decoy.arsc":     poison_arsc(),

    # ── Asset-tree decoys: apktool copies assets/ as-is, but some scanners
    #    (jadx, MobSF) try to parse XML/JSON inside assets and choke.
    "assets/_decoy_manifest.xml": poison_axml(),
    "assets/_decoy_resources.arsc": poison_arsc(),
}
# Append using STORED so headers look "normal" but content is junk.
with zipfile.ZipFile(src, "a", zipfile.ZIP_STORED, allowZip64=False) as z:
    existing = set(z.namelist())
    for name, data in poison.items():
        if name in existing:
            continue
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_STORED
        zi.external_attr = 0o644 << 16
        z.writestr(zi, data)
print("    %d poison entries appended." % len(poison))
PYEOF

    # (d) Verify the APK still validates after hardening.
    echo "  [d] Verifying signature integrity post-hardening ..."
    if "$APKSIGNER" verify --print-certs "$RELEASE_APK" > /dev/null 2>&1; then
        echo "    Signature OK — APK installable on Android."
    else
        # Poison entries appended AFTER signing invalidate the v2/v3 signature
        # block coverage. Re-sign one more time so the APK installs cleanly,
        # but keep the poison entries (they're now part of the signed set,
        # which is fine — they're still junk to decompilers).
        echo "    Re-signing after poison injection ..."
        "$APKSIGNER" sign \
            --ks "$KEYSTORE" \
            --ks-key-alias "$KEY_ALIAS" \
            --ks-pass "pass:$STORE_PASS" \
            --key-pass "pass:$KEY_PASS" \
            --v1-signing-enabled false \
            --v2-signing-enabled true \
            --v3-signing-enabled true \
            --v4-signing-enabled true \
            "$RELEASE_APK" 2>&1 | sed 's/^/      /'
        "$APKSIGNER" verify "$RELEASE_APK" > /dev/null 2>&1 \
            && echo "    Signature OK after re-sign." \
            || echo "    WARNING: signature still failing — inspect manually."
    fi

    HARDENED_SIZE=$(ls -lh "$RELEASE_APK" | awk '{print $5}')
    echo "  Hardened release APK: $RELEASE_APK ($HARDENED_SIZE)"
    echo "  Anti-decompile layers active:"
    echo "    R8 full mode + ProGuard (5-pass, log strip, repackaging)"
    echo "    Look-alike obfuscation dictionary (I/l/O/0)"
    echo "    v1 JAR signature stripped (v2 + v3 + v4 only)"
    echo "    Poison ZIP entries (fake classes0.dex, .bak files, BOM names)"
    echo "    Resource-tree poisoning (corrupted AXML in res/xml, layout, menu,"
    echo "      anim, drawable, values + decoy resources.arsc + assets decoys)"
    echo "    zipalign -p 4 (page-aligned native libs)"
else
    echo "  Skipping hardening — release APK not produced."
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  BUILD COMPLETE"
ls -lh "$ROOT_DIR/apk-output/"*.apk 2>/dev/null | awk '{print "  "$9" ("$5")"}'
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
