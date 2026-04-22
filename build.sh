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

    # (c) Two-part hardening pass:
    #     (c1) TAMPER the REAL resources.arsc and AndroidManifest.xml in-place
    #          with manipulations Android's ResTable / ResXMLTree tolerate
    #          (per-chunk size fields are authoritative for Android), but that
    #          break apktool / aapt2 / jadx, which read to EOF and validate
    #          flags/types strictly.
    #     (c2) Plant decoy entries (fake dex, .bak files, corrupted res/ XML).
    #     Both done in a single APK rebuild so we re-sign exactly once.
    echo "  [c] Tampering resources.arsc + AndroidManifest.xml + planting decoys ..."
    python3 - << PYEOF
import zipfile, os, random, struct, shutil
random.seed(0xC0FFEE)
src = "$RELEASE_APK"
tmp = src + ".rebuild"

# ─────────────────────────────────────────────────────────────────────────────
#  TAMPERING THE REAL FILES
# ─────────────────────────────────────────────────────────────────────────────
#
#  resources.arsc layout (relevant pieces):
#    +0  ResChunk_header
#         u16 type      (= 0x0002 RES_TABLE_TYPE)
#         u16 headerSize(= 12)
#         u32 size      (size of THIS chunk + all sub-chunks; Android trusts this)
#    +8  u32 packageCount
#    +12 ResStringPool_header (first sub-chunk)
#         u16 type      (= 0x0001 RES_STRING_POOL_TYPE)
#         u16 headerSize(= 28)
#         u32 size
#         u32 stringCount
#         u32 styleCount
#         u32 flags     ← Android masks with (SORTED|UTF8) = 0x101; ignores rest.
#         u32 stringsStart
#         u32 stylesStart
#
#  We do three things Android tolerates but apktool/aapt2 do NOT:
#    (1) OR reserved bits into ResStringPool.flags. Android's ResStringPool::
#        setTo only checks (flags & UTF8_FLAG); apktool's StringBlock and some
#        forks assert "(flags & ~(SORTED|UTF8)) == 0" or treat unknown bits as
#        a corrupt pool and abort.
#    (2) Append a crafted "phantom" ResChunk after ResTable_header.size. Android
#        stops at header.size and never sees it. apktool's ARSCDecoder reads
#        chunks sequentially until EOF and hits "unknown chunk type 0xDEAD" /
#        "negative array size".
#    (3) Pad the file with random bytes after the phantom chunk so length-based
#        heuristics in jadx / MobSF also break.
#
def tamper_arsc(data: bytes) -> bytes:
    if len(data) < 28: return data
    rt_type, rt_hsize, rt_size, pkg_count = struct.unpack_from("<HHII", data, 0)
    if rt_type != 0x0002:
        return data  # not a ResTable, leave alone
    sp_off = rt_hsize  # ResStringPool starts right after ResTable_header
    sp_type = struct.unpack_from("<H", data, sp_off)[0]
    out = bytearray(data)
    if sp_type == 0x0001:
        flags_off = sp_off + 16   # offset of 'flags' inside ResStringPool_header
        flags = struct.unpack_from("<I", out, flags_off)[0]
        # Reserved bits Android ignores: 0x80000000 + 0x40000000 + 0x00010000.
        struct.pack_into("<I", out, flags_off, flags | 0x80000000 | 0x40000000 | 0x00010000)
    # Phantom chunk: fake type 0xDEAD, claimed size that overflows int32.
    phantom = struct.pack("<HHII",
                          0xDEAD, 0x000C,
                          0xFFFFFFFF,           # size — overflows on signed parse
                          0x7FFFFFFF) + os.urandom(512)
    out.extend(phantom)
    # Random tail padding.
    out.extend(os.urandom(random.randint(256, 1024)))
    # CRITICAL: do NOT touch ResTable_header.size — Android uses it to bound
    # parsing. Trailing junk past header.size is silently ignored at runtime.
    return bytes(out)

#
#  AndroidManifest.xml + every compiled res/*.xml (AXML) layout:
#    +0  ResXMLTree_header   (u16 type=0x0003, u16 headerSize=8, u32 size)
#    +8  ResStringPool       (the file's string pool — DO NOT relocate; XML
#                             nodes reference it by INDEX, not offset)
#    +N  ResXMLTree_resourceMap  (optional, type 0x0180)
#    +M  ResXMLTree_node chunks  (START_NS, START_ELT, END_ELT, END_NS, …)
#
#  Android's ResXMLTree::setTo() walks chunks via chunk.size and SKIPS unknown
#  chunk types — they're treated as harmless filler. apktool's
#  AXmlResourceParser switch-cases on chunk type and THROWS on anything that
#  isn't a known XML event type.
#
#  Previous attempt put the phantom *after* END_DOCUMENT — apktool's parser
#  exits at END_DOCUMENT and never sees it. Fix: insert the phantom INSIDE
#  the bounded chunk stream, right after the string pool / resource map,
#  *before* the first real XML node. Android iterates past it; apktool dies
#  on the first unknown chunk type before it ever reads a valid XML event.
#
#  Also tamper the StringPool.flags with reserved bits (Android masks them,
#  apktool's StringBlock validates them).
#
def tamper_axml(data: bytes) -> bytes:
    if len(data) < 8: return data
    t, hs, fsize = struct.unpack_from("<HHI", data, 0)
    if t != 0x0003 or hs != 8 or fsize != len(data):
        return data
    out = bytearray(data)
    # 1) StringPool flag tampering
    sp_off = hs
    if len(out) >= sp_off + 28:
        sp_type = struct.unpack_from("<H", out, sp_off)[0]
        if sp_type == 0x0001:
            flags_off = sp_off + 16
            flags = struct.unpack_from("<I", out, flags_off)[0]
            struct.pack_into("<I", out, flags_off,
                             flags | 0x80000000 | 0x40000000 | 0x00010000)
    # 2) Find insertion point: end of StringPool (+ optional ResourceMap),
    #    just before the first XML node chunk.
    insert_off = sp_off
    if len(out) >= sp_off + 8:
        sp_size = struct.unpack_from("<I", out, sp_off + 4)[0]
        insert_off = sp_off + sp_size
        # Optional resource-map chunk (type 0x0180)
        if len(out) >= insert_off + 8:
            rm_type, _, rm_size = struct.unpack_from("<HHI", out, insert_off)
            if rm_type == 0x0180:
                insert_off += rm_size
    if insert_off >= len(out):
        return bytes(out)
    # 3) Build a phantom chunk with VALID size field (Android skips it via
    #    chunk.size) but UNKNOWN type 0xDEAD (apktool throws).
    phantom_payload = os.urandom(56)
    phantom_size = 8 + len(phantom_payload)            # 64 bytes total
    phantom = struct.pack("<HHI", 0xDEAD, 0x0008, phantom_size) + phantom_payload
    out[insert_off:insert_off] = phantom
    # 4) Bump root header.size so Android's bounded iterator includes the
    #    phantom (and stops at the real END as before).
    struct.pack_into("<I", out, 4, fsize + phantom_size)
    return bytes(out)

def is_axml(b: bytes) -> bool:
    return len(b) >= 8 and b[0:4] == b'\x03\x00\x08\x00'

# ─────────────────────────────────────────────────────────────────────────────
#  DECOY ENTRIES (planted in res/, assets/, root)
# ─────────────────────────────────────────────────────────────────────────────
def poison_axml():
    header = struct.pack("<HHI", 0x0003, 0x0008, 0x10000000)
    spool  = struct.pack("<HHIIIIII",
                         0x0001, 0x001C,
                         0x10000000, 0x7FFFFFFF, 0, 0, 0x10000000, 0)
    return header + spool + os.urandom(512)

def poison_arsc_blob():
    return struct.pack("<HHII",
                       0x0002, 0x000C, 0x10000000, 0x7FFFFFFF) + os.urandom(2048)

decoys = {
    "classes0.dex":                 b"dex\n000\x00" + os.urandom(2048),
    "AndroidManifest.xml.bak":      poison_axml(),
    "resources.arsc.bak":           poison_arsc_blob(),
    "META-INF/services/\xef\xbb\xbfpoison": b"# decoy\n",
    "res/xml/_decoy0.xml":          poison_axml(),
    "res/xml/_decoy1.xml":          poison_axml(),
    "res/layout/_decoy0.xml":       poison_axml(),
    "res/layout/_decoy1.xml":       poison_axml(),
    "res/menu/_decoy.xml":          poison_axml(),
    "res/anim/_decoy.xml":          poison_axml(),
    "res/drawable/_decoy.xml":      poison_axml(),
    "res/values/_decoy.xml":        poison_axml(),
    "res/raw/_decoy.bin":           os.urandom(1024),
    "res/raw/_decoy.arsc":          poison_arsc_blob(),
    "assets/_decoy_manifest.xml":   poison_axml(),
    "assets/_decoy_resources.arsc": poison_arsc_blob(),
}

# ─────────────────────────────────────────────────────────────────────────────
#  REBUILD APK with tampered real entries + decoys
# ─────────────────────────────────────────────────────────────────────────────
tampered_arsc = 0
tampered_axml_files = []
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "resources.arsc":
            data = tamper_arsc(data); tampered_arsc += 1
        elif is_axml(data):
            new_data = tamper_axml(data)
            if new_data is not data and new_data != data:
                tampered_axml_files.append(item.filename)
                data = new_data
        new_item = zipfile.ZipInfo(item.filename)
        new_item.compress_type = item.compress_type
        new_item.external_attr = item.external_attr
        new_item.date_time = item.date_time
        zout.writestr(new_item, data)
    existing = set(zin.namelist())
    for name, blob in decoys.items():
        if name in existing: continue
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_STORED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, blob)

shutil.move(tmp, src)
print("    resources.arsc tampered:   %d" % tampered_arsc)
print("    AXML files tampered:       %d" % len(tampered_axml_files))
for f in tampered_axml_files[:8]:
    print("      - %s" % f)
if len(tampered_axml_files) > 8:
    print("      … and %d more" % (len(tampered_axml_files) - 8))
print("    Decoy entries planted:     %d" % len(decoys))
PYEOF

    # (d) Re-zipalign (rebuild in (c) reset alignment) and re-sign.
    echo "  [d] Re-zipalign + final sign (v2 + v3 + v4) ..."
    REALIGN="$ROOT_DIR/apk-output/.realign.apk"
    "$ZIPALIGN" -p -f 4 "$RELEASE_APK" "$REALIGN" > /dev/null
    mv "$REALIGN" "$RELEASE_APK"
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
    if "$APKSIGNER" verify "$RELEASE_APK" > /dev/null 2>&1; then
        echo "    Signature OK — APK installable on Android."
    else
        echo "    WARNING: signature failing — inspect manually."
    fi

    HARDENED_SIZE=$(ls -lh "$RELEASE_APK" | awk '{print $5}')
    echo "  Hardened release APK: $RELEASE_APK ($HARDENED_SIZE)"
    echo "  Anti-decompile layers active:"
    echo "    R8 full mode + ProGuard (5-pass, log strip, repackaging)"
    echo "    Look-alike obfuscation dictionary (I/l/O/0)"
    echo "    v1 JAR signature stripped (v2 + v3 + v4 only)"
    echo "    REAL resources.arsc tampered (reserved flag bits + phantom"
    echo "      0xDEAD chunk past header.size + tail padding)"
    echo "    REAL AndroidManifest.xml + every res/*.xml tampered (StringPool"
    echo "      flag bits + phantom 0xDEAD chunk INSIDE bounded chunk stream,"
    echo "      between StringPool and first XML node — apktool throws on it)"
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
