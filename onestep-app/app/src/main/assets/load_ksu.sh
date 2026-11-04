#!/system/bin/sh
if [ -e /proc/self/fd/7 ]; then exec >&7 2>&1; else exec > /data/local/tmp/ksu_load.log 2>&1; fi
set +e
echo "=== getenforce ==="; getenforce
echo "step1"
rm -f /data/local/tmp/kmsg_cap.txt
echo "step2"
( /data/local/tmp/kmsg_dumper /data/local/tmp/kmsg_cap.txt </dev/null >/dev/null 2>&1 & )
echo "step3 dumper started"
sleep 2
echo "step4 dumper file: $(ls -l /data/local/tmp/kmsg_cap.txt 2>&1)"
i=0; v=""
while [ $i -lt 90 ]; do
  v=$(cat /sys/module/module/parameters/sig_enforce 2>/dev/null)
  [ "$v" = "N" ] && break
  sleep 1; i=$((i+1))
done
echo "=== sig_enforce=$v (waited ${i}s) ==="
if [ "$v" != "N" ]; then
  echo "ABORT: sig_enforce=$v — the FLIP_SIG walk did not land; NOT loading"
  kill $KPID 2>/dev/null
  exit 0
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

NEI=$(grep ' kallsyms_lookup_size_offset$' /proc/kallsyms | head -1 | cut -d' ' -f1)
IT=$(grep ' init_task$' /proc/kallsyms | head -1 | cut -d' ' -f1)
CFID=$(grep ' __cfi_slowpath_diag$' /proc/kallsyms | head -1 | cut -d' ' -f1)
echo "nei=$NEI init_task=$IT cfi_diag=$CFID"
[ -z "$NEI" ] && { echo "ABORT: neighbour missing"; }

CC=$(addrd "$NEI" -987480)          # -0xF1158  (987480)
KZ=$(addrd "$IT" 1984)              # init_task+0x7c0 (zero byte)
echo "commit_creds=$CC  kasan_zero=$KZ  cfi_slowpath=$CFID"

{ echo "$CC T commit_creds"
  echo "$CFID T __cfi_slowpath"
  echo "$KZ B kasan_flag_enabled"
  cat /proc/kallsyms; } | grep -v ' sys_call_table$' > /data/local/tmp/fake_kallsyms
echo "sys_call_table lines in fake: $(grep -c ' sys_call_table$' /data/local/tmp/fake_kallsyms)"
echo "fake lines=$(wc -l < /data/local/tmp/fake_kallsyms)"
mount --bind /data/local/tmp/fake_kallsyms /proc/kallsyms; echo "bind_rc=$?"
for s in commit_creds __cfi_slowpath kasan_flag_enabled; do
  echo "  $s -> $(grep -m1 " $s\$" /proc/kallsyms | cut -d' ' -f1)"
done

echo "=== userspace env for KSU ==="
mkdir -p /data/adb/ksu /data/adb/modules 2>&1; echo "mkdir_rc=$?"
ls -ld /data/adb/ksu /data/adb/modules 2>&1
cat /sys/fs/selinux/policy > /data/local/tmp/pristine_policy 2>/dev/null
echo "pristine_policy=$(wc -c < /data/local/tmp/pristine_policy 2>/dev/null) bytes"
echo "=== SELinux policy injection (ksu domain) ==="
/data/local/tmp/magiskpolicy --apply /data/local/tmp/ksu_rules --live 2>&1 | tail -5
echo "magiskpolicy_rc=$?"
echo "ktx=$(cat /proc/self/attr/current 2>/dev/null)"
echo "hung_panic was=$(cat /proc/sys/kernel/hung_task_panic 2>/dev/null)"
echo 0 > /proc/sys/kernel/hung_task_panic 2>&1
echo "hung_panic now=$(cat /proc/sys/kernel/hung_task_panic 2>/dev/null)"
CTL=$(addrd "$IT" 4384584)   # init_task+0x42E748 = ctl_table.data field (entry+8)     # init_task + 0x42E740 = sysctl boot_id ctl_table
BUF=$(addrd "$IT" 6819677)     # init_task + 0x680F5D = sysctl_bootid buffer
echo "pristine_policy=/data/local/tmp/pristine_policy allow_shell=1"
echo "=== load_ko + ksud (no gap) ==="
/data/local/tmp/load_ko /data/local/tmp/kernelsu.new.ko "pristine_policy=/data/local/tmp/pristine_policy allow_shell=1" \
  > /data/local/tmp/loadko.log 2>&1
echo "load_ko_rc=$?"
echo "load_ko returned rc=$?" > /data/local/tmp/LOADKO_DONE; sync
# userspace must take over IMMEDIATELY: KSU late-load expects ksud right away
echo "=== expose KSU su to the shell uid ==="
ls -l /data/adb/ksu/bin/ 2>&1 | head -8
mkdir -p /data/adb/ksu/bin 2>&1
cp /data/local/tmp/ksudbin /data/adb/ksu/bin/su 2>&1; echo "inst_su=$?"
cp /data/local/tmp/ksudbin /data/adb/ksud 2>&1; echo "inst_ksud=$?"
chmod 755 /data/adb/ksu/bin/su /data/adb/ksud 2>&1
ls -l /data/adb/ksu/bin/su /data/adb/ksud 2>&1
rm -f /data/local/tmp/su; cp /data/adb/ksu/bin/su /data/local/tmp/su 2>&1; echo "cp_su=$?"
chmod 755 /data/local/tmp/su 2>&1; echo "chmod=$?"
ls -l /data/local/tmp/su 2>&1
/data/local/tmp/su -c id 2>&1 | head -2
/data/local/tmp/ksud post-fs-data > /data/local/tmp/ksud_pfd.log 2>&1; echo "pfd=$?"
/data/local/tmp/ksud services > /data/local/tmp/ksud_svc.log 2>&1; echo "svc=$?"
tail -20 /data/local/tmp/loadko.log 2>&1
echo "=== lsmod ==="; lsmod 2>&1 | head -4
echo "=== ksud stages ==="
/data/local/tmp/ksud post-fs-data > /data/local/tmp/ksud_pfd.log 2>&1; echo "post-fs-data=$?"
/data/local/tmp/ksud services > /data/local/tmp/ksud_svc.log 2>&1; echo "services=$?"
/data/local/tmp/ksud boot-completed > /data/local/tmp/ksud_bc.log 2>&1; echo "boot-completed=$?"
echo "--- pfd ---"; tail -6 /data/local/tmp/ksud_pfd.log 2>&1
echo "--- svc ---"; tail -6 /data/local/tmp/ksud_svc.log 2>&1
echo "=== su probe ==="
/data/local/tmp/ksud --help > /data/local/tmp/ksud_help.log 2>&1; echo "help_rc=$?"
head -5 /data/local/tmp/ksud_help.log 2>&1
echo "=== dmesg ==="; dmesg 2>/dev/null | grep -iE 'kernelsu|KernelSU|cfi|panic' | tail -6
umount /proc/kallsyms 2>/dev/null
echo "=== DONE ==="
