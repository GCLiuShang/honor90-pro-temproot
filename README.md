English | [中文](README.zh-CN.md)

# HONOR 90 Pro (REP-AN00) Temporary Root — GhostLock + KernelSU + One-Tap App

A verified port of the **GhostLock** exploit chain (CVE-2026-43499) to the
**HONOR 90 Pro `REP-AN00`** (MagicOS 9.0.0.230, kernel
`5.10.236-android12-9-g3c84ac3cc469`), with a one-tap Android app and a
root-cause analysis of the `C1 fire` crash that used to reset the device
roughly one attempt in three.

> **Fork lineage**:
> [yakidango-official/GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT)
> (Honor 80 GT original PoC) → [cyrrp/honor90-ghostlock](https://github.com/cyrrp/honor90-ghostlock)
> (Honor 90 REA-NX9 port) → **this fork** (Honor 90 Pro REP-AN00 port, fire
> timing fixes, one-tap app, C1-fire analysis).

> **Upstream support** (unchanged by this fork): the parent repository also
> carries the Honor 80 GT (AGT-AN00, MagicOS 8.0.0.128 … 9.0.0.230) and the
> Honor 90 (REA-NX9, 9.0.0.211) targets — see
> [cyrrp/honor90-ghostlock](https://github.com/cyrrp/honor90-ghostlock).

---

## 1. How the exploit works (one page)

CVE-2026-43499 is a use-after-free in the kernel `rtmutex` waiter handling.
The exploit turns it into an arbitrary write:

1. **Ghost waiter** — a `FUTEX_WAIT_REQUEUE_PI`/`FUTEX_LOCK_PI` dance ends in
   `EDEADLK`; the waiter's `rt_mutex_waiter` is dequeued but
   `task->pi_blocked_on` still points at it. That stale waiter lives on the
   waiter thread's own kernel stack (the "ghost").
2. **Plant** — `rt_sigreturn` → `restore_fpsimd_context` copies 512 bytes of
   fpsimd state from the user sigframe onto the deepest part of that stack,
   overwriting the ghost's fields with a fake `tree_entry` / `lock` / `prio`.
3. **Fire** — `sched_setattr(waiter_tid)` → `__sched_setscheduler` →
   `rt_mutex_adjust_pi` → `rt_mutex_adjust_prio_chain` walks the planted tree;
   the `pi_tree` erase is the actual arbitrary write (`*pi_left = pi_pc`).
4. **Sysctl walk** — five stages reuse the primitive: hijack
   `nf_loggers` → read back the KASLR slide → write `init_cred` into the anchor
   task → flip SELinux to permissive → skip the anti-root (rscan) flag.
5. **KernelSU** — a KernelSU LKM is loaded through the temporary root and
   exposes `su`.

Everything runs as `shell` (uid 2000) via Shizuku; no PC is required.

---

## 2. What we did (the experiments)

### 2.1 Porting to REP-AN00

The official firmware and the `boot.img`/kernel image extracted from it are
**not** redistributed here. We recovered the symbol table from the device's own
kernel with `vmlinux-to-elf` (≈140k symbols, base `0xffffffc008000000`) and
confirmed every structure offset by disassembly:

- `task_struct`: `real_cred 0x7b0`, `cred 0x7b8` (stock-GKI is `0x780` — this
  vendor build is `0x38` larger), `pi_lock 0x8b4`, `pi_waiters 0x8b8`,
  `pi_top_task 0x8c8`, `prio 0x84`, `normal_prio 0x8c`, `sched_class 0x98`;
- `mm_struct` slab stride is `0x400` (not the inherited `0x3c0`); with the
  wrong stride KernelSnitch failed 3/3, with `0x400` the anchor was found on
  the first try;
- `rt_sched_class` at `0x23449e0`;
- the sigret carrier's landing byte was swept on the device
  (`SLIDE_SIGRET_BUF_BYTE=0x10`).

See [`docs/REP-AN00_PORT.md`](docs/REP-AN00_PORT.md) for the full provenance.

### 2.2 Baseline and the `C1 fire` crash

Six clean boots of the short chain (root → permissive → log collection):
**4 succeeded, 2 hard-reset**. Both failures stopped at the *first* fire
(`W3p planted` printed, no `C2 fired-ok`), i.e. the crash was in the fire, not
in the later KernelSU stages.

### 2.3 Instrumentation

To see what the kernel actually saw we armed kprobes over `tracefs`:

| probe | function | what it gave us |
|---|---|---|
| `pi` | `rt_mutex_adjust_pi` | `task->pi_blocked_on` (the ghost pointer) + its first words |
| `rfps` | `restore_fpsimd_context` | the waiter's stack reference point at plant time |
| `sch` | `__schedule` | deepest stack depth of the IRQ/schedule path on that stack |
| `tick` | `scheduler_tick` | tick-path depth |
| `kmsg` | `/dev/kmsg` dumper | kernel messages that survive a reset |

Together with a cycle-counter trace (`D1`) and durable `O_SYNC` log files, this
produced the geometry used below.

### 2.4 Root cause of the `C1 fire` crash

The fire must happen while the ghost is **resident and untouched** after the
plant. The waiter keeps the plant alive by spinning in userspace for a bounded
"hold". Everything that runs on the waiter's kernel stack inside that window
can destroy the ghost — and the restore path itself is the worst offender: it
`memset`s 0x210 bytes and then copies 0x200 bytes over the ghost region
(`[0x250,0x460)` by depth), so a second `tgkill`/`rt_sigreturn` round rewrites
what the first round planted.

Measured values on this device:

| quantity | value |
|---|---|
| plant copy base depth | `0x460` |
| ghost word 0 depth (plant byte `0x10`) | `0x450` |
| ghost range | `[0x408, 0x458)` |
| fire-path frame bottom | `0x360` |
| IRQ/`__schedule` deepest frame bottom on the waiter stack | `0x390` (58 samples; **0** events inside the ghost window `(0x338,0x458)`) |
| in-process `O_SYNC` write | **> 50 µs and < 1 ms** |
| old userspace hold | **50 µs** |

The original code wrote two `O_SYNC` diagnostics **between** seeing the
"plant is fresh" flag and calling `sched_setattr`:

- the `D1` timing line (unconditional), and
- the `C1 fire` marker (gated on `WALK_TRACE`, which `env_defaults.h` bakes
  on by default).

Either write blocks for longer than the 50 µs hold, so the waiter gives up,
clears the flag and starts another round; the fire then lands in the middle of
the restore and reads a torn ghost. The crashing log signature is exactly:

```
W3p planted
D1 ... dt=95 cyc            ← the handshake itself was fine
W3p planted                 ← the waiter re-stamped: the ghost was rewritten
(no C2 fired-ok)            ← the fire crashed
```

### 2.5 Causal A/B (same binary, only an env var changed)

| configuration | result |
|---|---|
| `SLIDE_D1_TRACE=1` (restore the pre-fire `O_SYNC` write) | **2/2 crashes**, identical signature |
| no pre-fire `O_SYNC` write | **5/5 successes**, 25/25 fires `C2 fired-ok`, **0** re-stamps |
| 1 ms hold + `SLIDE_D1_TRACE=1` | **success**, 5/5 fires, **0** re-stamps — 1 ms fully absorbs the write |
| 1 ms hold, no trace (default) | consecutive clean boots all succeed |

### 2.6 Why self-fire does not work

Letting the waiter call `sched_setattr` on itself removes the cross-thread
window, but the fire syscall then reuses the *same* kernel stack and its own
path is too deep:

- fire frames end at `0x360`, but the SELinux hook runs **before**
  `rt_mutex_adjust_pi`: `security_task_setscheduler 0x30` →
  `selinux_task_setscheduler 0x20` → `avc_has_perm 0xB0` (a cache hit adds
  `avc_lookup 0x40`; a miss adds `avc_compute_av 0x50` and more), reaching
  `0x3A0 … 0x400+` — inside the ghost range;
- empirically the self-fire build crashed at the fire as well, so the knob was
  left as an experiment (`SLIDE_SG_SELF`, default off).

### 2.7 Fix and validation

- no `O_SYNC` write between the plant handshake and the fire (the `C1` marker
  moved after the fire, `D1` is behind `SLIDE_D1_TRACE`);
- `SLIDE_SIGRET_GAP_US` default **50 → 1000** (1 ms hold);
- everything else (KernelSU load, app flow) unchanged.

Validation: short chain **10/10** clean boots (was 4/6); full chain
(exploit + KernelSU LKM) **5/5**; the one-tap app completed the whole flow
end-to-end as well. See [`docs/C1_FIRE_ROOTCAUSE.md`](docs/C1_FIRE_ROOTCAUSE.md)
for the full data and the residual risks (consumer preemption beyond 1 ms, the
softirq tail, and app-runtime load).

### 2.8 One-tap app

`onestep-app/` wraps the chain into a normal Android app:

- **two buttons**: *Start Shizuku* (uses an accessibility service to tap
  "start" and the permission dialog) and *One-tap root* (runs the kit through
  Shizuku's shell);
- **8-step task list** with an animated two-line scroller, a millisecond
  timer, and a `k/5` progress label while the five sysctl stages run;
- **device gate**: uptime ≥ 60 s and ≥ 4 GB free memory (no temperature or
  CPU-frequency gating);
- **tamper-resistant kit**: assets are mirrored into the app's private
  directory with a per-file md5 manifest; `stage_kit.sh` copies only from
  there and refuses anything that does not match;
- **teardown** after success: disable wireless debugging and stop Shizuku;
- real-time device status (accessibility / Shizuku / KernelSU / memory /
  uptime) and a bottom snackbar explaining why a greyed-out button is
  disabled.

### 2.9 Parallel space, payment protection and Find Device: the LSPosed patch module

While porting the temporary root we also mapped the root gates of three MagicOS
security features and turned the bypasses into a small LSPosed module shipped
inside the same APK.

| Component | root gate | bypass |
|---|---|---|
| Parallel space (`com.hihonor.parallelspace`) | `ApplicationUtil.isRoot()` (reads `msc.parallelspace.debug_mode`); the AssetManager **write** when setting the space password is refused by `com.hihonor.securityserver` (`HwKeystoreServiceImpl.isRoot()`, error `-319`) | hook `ApplicationUtil.isRoot()` → false **and** `setprop msc.parallelspace.debug_mode 1` after root (belt and braces) |
| Payment protection (`com.hihonor.trustspace`) | risk scanner `getRootStatusSync() > 0 ? 0 : 6` (0 = risk) | hook `HwSecurityDiagnoseManager.getRootStatusSync()` → 0 |
| Space-password write (`com.hihonor.securityserver`) | `HwKeystoreServiceImpl.isRoot()` | hook → false (only needed when creating or resetting a space password) |

Find Device (`com.hihonor.findmydevice`) does **not** gate its UI on root — its
`SystemUtil.isRoot()` is only used for BI/cloud reporting — and killing the
`hwsecurity` HAL to "pass" payment protection breaks Find Device, so the module
never touches the HAL.

The APK is both the one-tap app and an LSPosed module (legacy Xposed API,
`assets/xposed_init` → `com.ghostlock.ksuapp.PatchEntry`, scopes in
`res/values/arrays.xml`). After installing, enable **一键GLKS** in the LSPosed
manager and tick `trustspace`, `securityserver`, `parallelspace`
(`findmydevice` optional). The new **安全补丁模块** screen in the app reports
whether the module is enabled and whether the required scopes are selected
(read from LSPosed's `modules_config.db`), exposes per-hook switches (default
on) and "restart service" buttons that force-stop a target so LSPosed
reinjects it.

Details and evidence: [`docs/PARALLEL_SPACE.md`](docs/PARALLEL_SPACE.md),
[`docs/SECURITY_PATCH.md`](docs/SECURITY_PATCH.md),
[`docs/SECURITY_PATCH_MODULE_DESIGN.md`](docs/SECURITY_PATCH_MODULE_DESIGN.md).

---

## 3. Changes relative to the upstream Honor 90 port

| Path | Change |
|---|---|
| `exploit/src/targets/honor-REP-AN00_9.0.0.230/` | **new target** (offsets reverse-engineered from the device kernel; provenance in the file header) |
| `exploit/src/targets/honor-REP-AN00_9.0.0.230-mcast/` | experimental mcast carrier variant (not enabled by default) |
| `exploit/src/slide.c` | fire timing: 1 ms hold (`SLIDE_SIGRET_GAP_US` 50→1000), no pre-fire `O_SYNC`, `D1` behind `SLIDE_D1_TRACE`, `C1` marker after the fire, `SLIDE_SG_SELF` self-fire experiment |
| `exploit/src/common.h` | `MM_STRUCT_SZ` `0x3c0` → `0x400` (REP-AN00 slab stride), self-contained/walk-target knobs |
| `exploit/src/sysctl.c` | diagnostic walk-target override and self-contained chain mode |
| `exploit/docker-build.sh` | proxy environment for the persistent builder |
| `ksu/ksu_ko_build.sh` | KernelSU LKM build adjustments |
| `onestep-app/` | **new**: the one-tap Android app (Java, no AndroidX), build scripts, binary deps |
| `research/` | **new**: offline rtmutex/chain models, device probe scripts, reports |
| `docs/C1_FIRE_ROOTCAUSE.md`, `docs/REP-AN00_PORT.md`, `docs/DEPENDENCIES.md` | **new** |
| `onestep-app/.../PatchEntry.java`, `PatchSettingsActivity.java`, `res/values/arrays.xml`, `assets/xposed_init`, `libs/xposed-api-stub.jar`, `xposed-stub/` | **new**: LSPosed security-patch module (parallel space / payment protection / space-password root gates) and its settings screen |
| `docs/PARALLEL_SPACE.md`, `docs/SECURITY_PATCH.md`, `docs/SECURITY_PATCH_MODULE_DESIGN.md` | **new** |
| `perf_test.c`, `trace_reader.c`, `exploit/enforcing_test.sh` | **new** diagnostic tools |
| `exploit/*.log` | device test logs (git-ignored) |

---

## 4. Repository layout

```
exploit/            GhostLock source (C), target definitions, docker build
  src/targets/honor-REP-AN00_9.0.0.230/    ← our device
ksu/                KernelSU LKM build scripts, patches, userspace tools
onestep-app/        Android app (one-tap): app/src, deps/, build.sh
research/           C1-fire investigation: reports/, harness/, scripts/
docs/               port notes, C1-fire analysis, parallel space / security patch, dependencies
```

## 5. Build

Dependencies (Docker/NDK, Android SDK, KernelSU, runtime) are listed in
[`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md).

```sh
# 1) exploit -> exploit/build/honor-REP-AN00_9.0.0.230/bin/exploit_static
PROJECT=honor-REP-AN00_9.0.0.230 ./exploit/docker-build.sh

# 2) app (generates assets from deps/ + the exploit build)
cd onestep-app
./prepare-assets.sh
ANDROID_SDK=$HOME/projects/android-sdk ./build.sh     # -> build/app-signed.apk
```

Prebuilt APKs are attached to Releases.

## 6. Usage

1. Install the APK (or push the scripts in `research/scripts/` to
   `/data/local/tmp` and run them from `adb shell` / Shizuku).
2. Enable the accessibility service once
   (`Settings → Accessibility → 一键GLKS`).
3. Tap **Start Shizuku**, wait for *完成*.
4. Tap **One-tap root**, wait for *完成*; `su -c id` should print
   `uid=0 … u:r:ksu:s0`.
5. (Optional, for the security features) enable the built-in LSPosed module:
   open the LSPosed manager (KernelSU module `zygisk_lsposed`), enable
   **一键GLKS** and tick `com.hihonor.trustspace`, `com.hihonor.securityserver`,
   `com.hihonor.parallelspace` (`com.hihonor.findmydevice` optional). The app's
   **安全补丁模块** screen shows whether the scopes are complete and can
   restart the affected services.

## 7. Known limitations

- a small residual fire crash probability remains (see the C1-fire document,
  §7); the app lowers runtime UI load during the run to reduce interference;
- the app process may be reclaimed by the system; reopening shows *idle*;
- Shizuku occasionally needs an app restart to re-deliver the binder;
- the port targets REP-AN00 9.0.0.230 only — do not run it on other builds;
- the LSPosed module needs a one-time scope selection in the LSPosed manager;
  LSPosed applies it at process start, so use the in-app "restart service"
  buttons (or reboot) after changing scopes;
- a reboot drops the temporary root and `msc.parallelspace.debug_mode`; re-run
  the one-tap root (it writes the property again);
- payment protection still reports "USB debugging not disabled" while adb is
  on — that item is unrelated to root;
- Find Device must not be "fixed" by killing the `hwsecurity` HAL: the module
  deliberately leaves the HAL alone (killing it breaks Find Device).

## 8. Credits & license

- Original PoC: [yakidango-official/GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT);
- Honor 90 port: [cyrrp/honor90-ghostlock](https://github.com/cyrrp/honor90-ghostlock);
- KernelSU, Magisk (`magiskpolicy`), Shizuku: their respective authors;
- License: see [`LICENSE`](LICENSE).

> **Disclaimer**: this code is published for security research and for
> repairing devices you own. It deliberately manipulates kernel memory,
> disables SELinux and loads an unsigned kernel module. Use it at your own
> risk; do not use it on devices you do not own or where doing so is illegal.
> Official firmware packages and kernel/boot images are **not** redistributed
> here.
