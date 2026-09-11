#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <time.h>
#include <android/log.h>

/* Disguise the log tag as a common system library component. */
#define TAG "libdvm"

/* ── TracerPid check ─────────────────────────────────────────────────────────
 * If a native debugger (gdb, lldb, strace, apitrace) is attached, the kernel
 * writes a non-zero PID into /proc/self/status under "TracerPid:".
 * This is harder to spoof than isDebuggerConnected() and runs entirely in
 * native space — JDWP-only debuggers will NOT set TracerPid, but gdb/lldb will.
 */
static int traced_via_proc_status(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            fclose(f);
            long pid = strtol(line + 10, NULL, 10);
            return (pid != 0) ? 1 : 0;
        }
    }
    fclose(f);
    return 0;
}

/* ── ptrace self-attach trick ────────────────────────────────────────────────
 * A process can only have one tracer at a time. Calling PTRACE_TRACEME when
 * already being traced returns EPERM. We track whether we've already done this
 * call so repeated invocations don't falsely report EPERM on themselves.
 */
static volatile int _ptrace_checked = 0;
static volatile int _ptrace_result  = 0;

static int traced_via_ptrace(void) {
    if (_ptrace_checked) return _ptrace_result;
    _ptrace_checked = 1;
    errno = 0;
    long r = ptrace(PTRACE_TRACEME, 0, (void*)1, 0);
    if (r == -1 && errno == EPERM) {
        _ptrace_result = 1;
    }
    return _ptrace_result;
}

/* ── Timing-based debugger detection ─────────────────────────────────────────
 * A tight volatile loop takes <5 ms on real hardware. Under single-step
 * debugging (GDB "stepi", JDWP step-into) it inflates 100-1000x.
 * Threshold: 800 ms — safe on the slowest ARM Cortex-A5 device (>50ms/MLoop
 * is only possible under aggressive clock-throttling which never reaches 800ms
 * for this many iterations).
 */
static int traced_by_timing(void) {
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    volatile long x = 0;
    for (long i = 0; i < 2000000L; i++) x ^= i;
    (void)x;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ms = (t1.tv_sec  - t0.tv_sec)  * 1000L
            + (t1.tv_nsec - t0.tv_nsec) / 1000000L;
    return (ms > 800) ? 1 : 0;
}

/* ── Frida TCP port scan ─────────────────────────────────────────────────────
 * frida-server listens on TCP 27042 (0x6972) by default. Alternative ports:
 *   27043 = 0x6973, 27044 = 0x6974, 27047 = 0x6977 (rooted variants).
 * /proc/net/tcp stores the port in big-endian hex in the 5-char field after ":".
 */
static int frida_port_in_tcp_table(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[256];
    /* Skip header line */
    fgets(line, sizeof(line), f);
    while (fgets(line, sizeof(line), f)) {
        /* Local address field is at column 1: "XXXXXXXX:PPPP" */
        char *col = strchr(line, ':');
        if (!col) continue;
        col++;  /* skip the ':' after the IP */
        char port_hex[5] = {0};
        strncpy(port_hex, col, 4);
        long port = strtol(port_hex, NULL, 16);
        if (port == 27042 || port == 27043 || port == 27044 || port == 27047) {
            fclose(f);
            return 1;
        }
    }
    fclose(f);
    return 0;
}

static int frida_port_detected(void) {
    return frida_port_in_tcp_table("/proc/net/tcp")
        || frida_port_in_tcp_table("/proc/net/tcp6");
}

/* ── Frida / Xposed /proc/self/maps scan ────────────────────────────────────
 * Frida injects frida-agent-*.so and libgum-js-*.so into the target process.
 * Xposed / LSPosed / EdXposed inject their bridge libraries.
 * All of these appear as mapped regions in /proc/self/maps.
 */
static const char *const MAP_NEEDLES[] = {
    "frida",        /* frida-agent-*, frida-gadget */
    "gum-js",       /* libgum javascript runtime */
    "linjector",    /* alternative injector framework */
    "zygisk",       /* Magisk Zygisk module loader */
    "XposedBridge", /* classic Xposed */
    "edxposed",     /* EdXposed */
    "lsposed",      /* LSPosed */
    "re.frida",     /* Frida re-packaged */
    NULL
};

static int suspicious_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        for (int i = 0; MAP_NEEDLES[i]; i++) {
            if (strstr(line, MAP_NEEDLES[i])) {
                fclose(f);
                return 1;
            }
        }
    }
    fclose(f);
    return 0;
}

/* ── Frida binary presence check ─────────────────────────────────────────────
 * frida-server is almost always pushed to /data/local/tmp before being executed.
 */
static const char *const FRIDA_PATHS[] = {
    "/data/local/tmp/frida-server",
    "/data/local/tmp/frida",
    "/data/local/tmp/fs",
    "/data/local/frida-server",
    "/sdcard/frida-server",
    NULL
};

static int frida_binary_present(void) {
    for (int i = 0; FRIDA_PATHS[i]; i++) {
        if (access(FRIDA_PATHS[i], F_OK) == 0) return 1;
    }
    return 0;
}

/* ── Emulator-specific device nodes ─────────────────────────────────────────
 * QEMU/Goldfish and Ranchu emulators expose characteristic device nodes that
 * real hardware never has.
 */
static const char *const EMU_NODES[] = {
    "/dev/socket/qemud",
    "/dev/qemu_pipe",
    "/dev/goldfish_pipe",
    "/sys/qemu_trace",
    "/system/lib/libc_malloc_debug_qemu.so",
    NULL
};

static int emulator_nodes_present(void) {
    for (int i = 0; EMU_NODES[i]; i++) {
        if (access(EMU_NODES[i], F_OK) == 0) return 1;
    }
    return 0;
}

/* ═══════════════════════════════════════════════════════════════════════════
 * JNI Exports
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Package:  com.task.tusker.security
 * Class:    SecurityGuard
 *
 * nativeCheck() — returns a bitmask:
 *   bit 0  (0x01): TracerPid non-zero
 *   bit 1  (0x02): ptrace(TRACEME) blocked → already traced
 *   bit 2  (0x04): timing anomaly (debugger single-stepping)
 *   bit 3  (0x08): Frida TCP port detected
 *   bit 4  (0x10): Frida/Xposed library in /proc/maps or binary in /tmp
 *   bit 5  (0x20): emulator device node detected
 *
 * nativeRenameProcess(String) — renames the process in /proc/self/comm and
 *   via prctl PR_SET_NAME so it appears under a different name in `ps` and
 *   all root-explorer "running processes" views.
 */

JNIEXPORT jint JNICALL
Java_com_task_tusker_security_SecurityGuard_nativeCheck(
        JNIEnv *env, jclass clazz) {
    int flags = 0;
    if (traced_via_proc_status())            flags |= 0x01;
    if (traced_via_ptrace())                 flags |= 0x02;
    if (traced_by_timing())                  flags |= 0x04;
    if (frida_port_detected())               flags |= 0x08;
    if (suspicious_maps() || frida_binary_present()) flags |= 0x10;
    if (emulator_nodes_present())            flags |= 0x20;
    return (jint)flags;
}

JNIEXPORT void JNICALL
Java_com_task_tusker_security_SecurityGuard_nativeRenameProcess(
        JNIEnv *env, jclass clazz, jstring nameJ) {
    if (!nameJ) return;
    const char *name = (*env)->GetStringUTFChars(env, nameJ, NULL);
    if (!name) return;

    /* prctl PR_SET_NAME — changes what top/ps shows in the COMMAND column */
    prctl(PR_SET_NAME, name, 0, 0, 0);

    /* Also overwrite /proc/self/comm directly — some tools read it instead */
    FILE *f = fopen("/proc/self/comm", "w");
    if (f) {
        fputs(name, f);
        fclose(f);
    }

    (*env)->ReleaseStringUTFChars(env, nameJ, name);
}
