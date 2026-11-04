#!/system/bin/sh
D=/data/local/tmp/dumplogs
mkdir -p $D
exec > $D/agent.out 2>&1
echo "== id =="; id; getenforce 2>&1; cat /proc/uptime
echo "== relax panic policy =="
echo 0 > /proc/sys/kernel/panic_on_oops 2>&1; echo "panic_on_oops=$(cat /proc/sys/kernel/panic_on_oops 2>&1)"
echo 0 > /proc/sys/kernel/hung_task_panic 2>&1; echo "hung_task_panic=$(cat /proc/sys/kernel/hung_task_panic 2>&1)"
echo "== start kernel log daemons =="
rm -f /data/local/tmp/kmsg_live.txt /data/local/tmp/kmsg_sync.txt
setsid /data/local/tmp/kmsg_dumper /data/local/tmp/kmsg_sync.txt </dev/null >/dev/null 2>&1 &
sleep 2
sleep 1
ps -A -o PID,NAME 2>/dev/null | grep -E "cat$|kmsg" | head -5
ls -la /data/local/tmp/kmsg_sync.txt 2>&1
echo "== pstore ls =="; ls -la /sys/fs/pstore/ 2>&1
for f in /sys/fs/pstore/*ramoops*; do [ -f "$f" ] && cp "$f" "$D/" 2>/dev/null; done
echo "== dmesg snapshot =="; dmesg > $D/dmesg_boot.txt 2>&1; echo "size=$(wc -c < $D/dmesg_boot.txt)"
echo "== tunables =="
for f in panic_on_oops hung_task_panic hung_task_timeout_secs softlockup_panic hardlockup_panic watchdog_thresh panic; do
  printf "%s=" $f; cat /proc/sys/kernel/$f 2>&1
done
chmod -R 777 $D /data/local/tmp/kmsg_sync.txt 2>/dev/null
sync
echo "DONE"
