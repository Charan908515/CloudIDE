// PTY support — JNI wrapper around posix_openpt + fork + exec.
//
// This is what gives our terminal a *real* controlling TTY (same as Termux).
// Without it, apt/dpkg/python REPL/anything that calls isatty() behaves
// differently than in a real terminal — apt aborts dpkg with exit code 100,
// dpkg suppresses output, line buffering breaks, etc.

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#define TAG "cloudide-pty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

// Convert a Java String[] into a NULL-terminated char** the C runtime can use.
// Caller must free via free_string_array().
static char** strings_from_jarray(JNIEnv* env, jobjectArray jarr) {
    jsize len = (*env)->GetArrayLength(env, jarr);
    char** out = (char**) calloc(len + 1, sizeof(char*));
    if (!out) return NULL;
    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jarr, i);
        const char* cs = (*env)->GetStringUTFChars(env, js, NULL);
        out[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, js, cs);
        (*env)->DeleteLocalRef(env, js);
    }
    out[len] = NULL;
    return out;
}

static void free_string_array(char** arr) {
    if (!arr) return;
    for (char** p = arr; *p; p++) free(*p);
    free(arr);
}

// Java_..._createPty(args, env, cwd, rows, cols, pidOut) -> master_fd, or -1.
JNIEXPORT jint JNICALL
Java_com_cloudide_android_data_terminal_PtyProcess_createPty(
        JNIEnv* env, jclass clazz,
        jobjectArray jargs, jobjectArray jenv, jstring jcwd,
        jint rows, jint cols, jintArray jpidOut) {

    char** args = strings_from_jarray(env, jargs);
    char** envp = strings_from_jarray(env, jenv);
    const char* cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);

    if (!args || !envp || !cwd || !args[0]) {
        LOGE("createPty: missing args");
        if (args) free_string_array(args);
        if (envp) free_string_array(envp);
        if (cwd)  (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return -1;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) { LOGE("posix_openpt: %s", strerror(errno)); goto fail; }
    if (grantpt(master) < 0) { LOGE("grantpt: %s", strerror(errno)); close(master); goto fail; }
    if (unlockpt(master) < 0) { LOGE("unlockpt: %s", strerror(errno)); close(master); goto fail; }

    char slavePath[256];
    if (ptsname_r(master, slavePath, sizeof(slavePath)) != 0) {
        LOGE("ptsname_r: %s", strerror(errno));
        close(master);
        goto fail;
    }

    // Initial window size — programs that ask via TIOCGWINSZ get something
    // sane instead of 0x0 (which makes ncurses-style apps refuse to draw).
    struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols, 0, 0 };
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) { LOGE("fork: %s", strerror(errno)); close(master); goto fail; }

    if (pid == 0) {
        // ── Child ────────────────────────────────────────────────────────
        // Detach from parent's controlling TTY (if any), then make the slave
        // PTY our controlling TTY. dup2 the slave onto stdin/stdout/stderr
        // so the exec'd program inherits a real TTY for fd 0/1/2.
        close(master);
        if (setsid() < 0) _exit(125);

        int slave = open(slavePath, O_RDWR);
        if (slave < 0) _exit(126);

        ioctl(slave, TIOCSCTTY, 0);

        // Echo + canonical mode are reasonable defaults; the shell will
        // change them as it sees fit (e.g. bash turns echo off in raw mode).
        struct termios tio;
        if (tcgetattr(slave, &tio) == 0) {
            tio.c_iflag |= ICRNL;       // CR -> NL on input
            tio.c_oflag |= OPOST | ONLCR; // NL -> CR-NL on output
            tio.c_lflag |= ECHO | ECHOE | ECHOK | ECHOCTL | ECHOKE | ICANON | ISIG;
            tcsetattr(slave, TCSANOW, &tio);
        }

        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);

        if (chdir(cwd) != 0) {
            // Not fatal — proot will set its own --cwd. Continue.
        }

        execve(args[0], args, envp);
        // execve only returns on error
        _exit(127);
    }

    // ── Parent ───────────────────────────────────────────────────────────
    // Hand the PID back via the IntArray output param, return master fd.
    jint pidVal = (jint) pid;
    (*env)->SetIntArrayRegion(env, jpidOut, 0, 1, &pidVal);

    free_string_array(args);
    free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    return master;

fail:
    if (args) free_string_array(args);
    if (envp) free_string_array(envp);
    if (cwd)  (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_cloudide_android_data_terminal_PtyProcess_setWindowSize(
        JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols, 0, 0 };
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : errno;
}

JNIEXPORT jint JNICALL
Java_com_cloudide_android_data_terminal_PtyProcess_waitForExit(
        JNIEnv* env, jclass clazz, jint pid) {
    int status = 0;
    pid_t r;
    do { r = waitpid((pid_t) pid, &status, 0); }
    while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status))   return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_cloudide_android_data_terminal_PtyProcess_killProcess(
        JNIEnv* env, jclass clazz, jint pid, jint sig) {
    if (pid > 0) kill((pid_t) pid, sig);
}

JNIEXPORT void JNICALL
Java_com_cloudide_android_data_terminal_PtyProcess_closeFd(
        JNIEnv* env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}
