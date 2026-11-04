#!/system/bin/sh
touch /data/local/tmp/agent_started 2>/dev/null
D=/data/local/tmp/dumplogs
mkdir -p $D
exec > $D/agent17.out 2>&1
echo 0 > /proc/sys/kernel/panic_on_oops
echo 0 > /proc/sys/kernel/hung_task_panic
rm -f /data/local/tmp/kmsg_sync17.txt /data/local/tmp/pi17.log
setsid /data/local/tmp/kmsg_dumper /data/local/tmp/kmsg_sync17.txt </dev/null >/dev/null 2>&1 &
T=/sys/kernel/tracing; [ -d "$T" ] || T=/sys/kernel/debug/tracing
cd $T || exit 1
echo 0 > tracing_on
echo 0 > events/enable 2>/dev/null
: > trace
: > kprobe_events 2>/dev/null
echo 'p:pi rt_mutex_adjust_pi task=%x0 pib=+0x8d0(%x0) w0=+0x0(+0x8d0(%x0)) w1=+0x8(+0x8d0(%x0)) w2=+0x10(+0x8d0(%x0)) w3=+0x18(+0x8d0(%x0)) w5=+0x28(+0x8d0(%x0)) w6=+0x30(+0x8d0(%x0)) w7=+0x38(+0x8d0(%x0)) w8=+0x40(+0x8d0(%x0))' >> kprobe_events 2>&1
echo 'pib != 0' > events/kprobes/pi/filter 2>&1
echo 1 > events/kprobes/enable 2>&1
echo 1 > tracing_on
killall trace_reader_bin 2>/dev/null; sleep 0.2
setsid /data/local/tmp/trace_reader_bin /data/local/tmp/pi17.log </dev/null >/dev/null 2>&1 &
chmod 666 /data/local/tmp/pi17.log /data/local/tmp/kmsg_sync17.txt 2>/dev/null
touch /data/local/tmp/armed_ok 2>/dev/null
sync
echo DONE
