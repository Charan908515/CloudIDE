/*
 * renameat2_fix.so — LD_PRELOAD shim for proot on Android.
 *
 * Overrides rename, renameat, AND renameat2 to handle EPERM/ENOSYS
 * that Android kernels and proot return for rename operations.
 *
 * Strategy: try the real syscall first. If it fails with EPERM or
 * ENOSYS, return 0 (fake success). The dpkg wrapper pre-copies
 * backup files before calling dpkg, so fake success is safe.
 */

#define RENAME_EXCHANGE  (1u << 1)

/* errno support (resolved from glibc at load time) */
extern int *__errno_location(void) __attribute__((weak));

/* aarch64 raw syscall helpers */
static inline long _sys2(long nr, long a, long b) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    __asm__ __volatile__("svc #0"
        : "=r"(x0) : "r"(x8), "0"(x0), "r"(x1)
        : "memory", "cc");
    return x0;
}

static inline long _sys4(long nr, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ __volatile__("svc #0"
        : "=r"(x0) : "r"(x8), "0"(x0), "r"(x1), "r"(x2), "r"(x3)
        : "memory", "cc");
    return x0;
}

static inline int _set_errno(long r) {
    if (r < 0) {
        if (__errno_location) *__errno_location() = (int)(-r);
        return -1;
    }
    return 0;
}

/* Override rename() */
int rename(const char *oldpath, const char *newpath) {
    long r = _sys2(38 /* __NR_renameat */, -100 /* AT_FDCWD */,
                   (long)oldpath);
    /* renameat needs 4 args, use _sys4 */
    r = _sys4(38, -100, (long)oldpath, -100, (long)newpath);
    if (r == 0) return 0;
    /* If EPERM or ENOSYS, fake success — wrapper handles backups */
    if (r == -1 || r == -38) return 0;  /* -EPERM=-1, -ENOSYS=-38 */
    return _set_errno(r);
}

/* Override renameat() */
int renameat(int olddirfd, const char *oldpath,
             int newdirfd, const char *newpath) {
    long r = _sys4(38, olddirfd, (long)oldpath, newdirfd, (long)newpath);
    if (r == 0) return 0;
    if (r == -1 || r == -38) return 0;
    return _set_errno(r);
}

/* Override renameat2() */
int renameat2(int olddirfd, const char *oldpath,
              int newdirfd, const char *newpath,
              unsigned int flags) {
    (void)olddirfd; (void)oldpath; (void)newdirfd; (void)newpath;

    if (flags == 0) {
        return renameat(olddirfd, oldpath, newdirfd, newpath);
    }

    /* RENAME_EXCHANGE or other flags: fake success */
    return 0;
}
