# CloudIDE Android — embedded Linux runtime (proot)

CloudIDE Android ships an embedded user-space Linux environment based on
**Alpine + proot** so projects can run `node`, `npm`, `python`, `pip`, `git`,
etc. directly on the phone — no Termux required.

## File layout

The runtime lives entirely inside the app's private storage:

```
/data/data/com.cloudide.android/files/
├── bin/
│   └── proot                            # the proot binary (ARM64 static)
├── rootfs/                              # Alpine root filesystem after first launch
│   ├── bin/
│   ├── etc/
│   ├── home/
│   ├── usr/
│   │   └── bin/{node, npm, pnpm, python3, pip, git, ...}
│   └── tmp/
└── projects/                            # mounted into rootfs at /workspace
    └── <driveFolderId>/                 # one per CloudIDE project
```

## Required binaries (user fetches once)

The repo does not ship the proot binary or the Alpine rootfs because they're
large and have their own licensing. Run this once on your dev machine:

```bash
cd android
./gradlew downloadRuntimeAssets
```

This downloads two files into `app/src/main/assets/cloudide-bootstrap/`:

| File | Where it comes from | Purpose |
|---|---|---|
| `proot` | https://github.com/proot-me/proot (or proot-distro) — static aarch64 build | The user-space chroot tool |
| `rootfs.tar.gz` | https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-...tar.gz | Alpine minirootfs |

If you're behind a firewall, drop the two files into
`app/src/main/assets/cloudide-bootstrap/` manually, named exactly as above.
The app verifies they exist on first launch.

After the assets are in place, build normally:

```bash
./gradlew assembleDebug
```

## What happens on first launch

1. The app checks `<filesDir>/.runtime-ready`. If absent:
   - Copy `assets/cloudide-bootstrap/proot` → `<filesDir>/bin/proot`, chmod 0755
   - Copy `assets/cloudide-bootstrap/rootfs.tar.gz` → temp file
   - Extract into `<filesDir>/rootfs/`
   - Write a `/etc/resolv.conf` so the rootfs has DNS (`nameserver 1.1.1.1`)
2. `ToolchainInstaller` runs:
   - `apk update`
   - `apk add --no-cache nodejs npm python3 py3-pip git curl bash`
   - `npm install -g pnpm` (recommended) — uses a global content-addressed
     store under `~/.local/share/pnpm/store`, then hard-links per-project
   - Creates `~/cloudide-venv` and writes a small `~/.profile` so any new
     shell auto-activates it
3. `<filesDir>/.runtime-ready` is touched. Subsequent launches skip steps 1–2.

Total first-launch work: ~30–90 seconds depending on the device and network.
After that, starting a shell is essentially instant.

## Centralized packages

You asked for a *single* `node_modules` and a *single* `venv` shared across
projects. Two choices:

### Recommended: pnpm + shared venv (what we install)
- pnpm uses a global, content-addressed package store at
  `~/.local/share/pnpm/store`. Any version of any package is downloaded
  exactly once. Each project still has its own `node_modules/`, but the
  files inside are *hard-links* into the global store, costing nearly no
  disk per project.
- Python's shared venv lives at `~/cloudide-venv`. Every shell auto-activates
  it; `pip install foo` updates the shared venv and `foo` is then available
  in every project.

### Strict single-store (your literal request)
If you'd rather have one literal `node_modules` shared by every project
(no per-project subfolder), set `NPM_CONFIG_PREFIX=~/.npm-global` and add
`~/.npm-global/lib/node_modules` to `NODE_PATH`. The runtime supports this
mode via a flag — see `ProotEnvironment.useStrictGlobalNodeModules`. Be
aware: the moment two projects need different versions of any dependency,
one of them breaks. pnpm avoids this entirely.

## Performance notes

- proot adds ~10–30% syscall overhead. `npm install` on a phone is slow
  regardless; expect 1–5 minutes for medium projects.
- Heavy native compilation (e.g. `npm install bcrypt`) often fails on Alpine
  because of musl/glibc incompatibilities. Pure-JS packages are fine.
- Python is similar: `pip install numpy` works (Alpine wheels exist for
  aarch64), `pip install torch` does not.

## Troubleshooting

- `proot: ptrace ... operation not permitted` — your kernel disabled ptrace
  for unprivileged processes. Run on a different device; there's no
  workaround in user-space.
- DNS errors in the rootfs — `/etc/resolv.conf` was lost. Re-create the
  rootfs by deleting `<filesDir>/rootfs/` and `<filesDir>/.runtime-ready`,
  then relaunch.
- "Toolchain install failed" — check the terminal output. Most often a
  network issue while running `apk update`.
