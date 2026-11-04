#!/system/bin/sh
# Agent v2: pi / rfps / sch / tick with a comm field + filters.
#  - sch  = __schedule   (deepest point of the IRQ return path)
#  - tick = scheduler_tick (tick path)
#  - rfps = restore_fpsimd_context (waiter stack reference; sp at depth 0x210)
#  - pi   = rt_mutex_adjust_pi (ghost pointer pib; word0 at depth 0x450)
# Filter: first try comm=="exploit_static" (valid now that comm is a field);
# fall back to a pid window around this agent's parent (the exploit process).
D=/data/local/tmp
mkdir -p $D/dumplogs
exec > $D/dumplogs/arm_depth2.out 2>&1
echo "== depth arm v2 =="
echo 0 > /proc/sys/kernel/panic_on_oops 2>/dev/null
echo 0 > /proc/sys/kernel/hung_task_panic 2>/dev/null
T=/sys/kernel/tracing; [ -d "$T" ] || T=/sys/kernel/debug/tracing
cd $T || exit 1
echo 0 > tracing_on
echo 0 > events/enable 2>/dev/null
: > trace
: > kprobe_events 2>/dev/null
PP=$PPID
echo "agent pid=$$ ppid=$PP"
echo 'p:pi rt_mutex_adjust_pi task=%x0 pib=+0x8d0(%x0) w0=+0x0(+0x8d0(%x0)) w7=+0x38(+0x8d0(%x0)) w8=+0x40(+0x8d0(%x0))' >> kprobe_events
echo "pi rc=$?"
echo 'p:rfps restore_fpsimd_context sp=$stack comm=$comm' >> kprobe_events
echo "rfps rc=$?"
echo 'p:sch __schedule sp=$stack comm=$comm' >> kprobe_events
echo "sch rc=$?"
echo 'p:tick scheduler_tick sp=$stack comm=$comm' >> kprobe_events
echo "tick rc=$?"
echo "-- probes --"; ls events/kprobes/
FILT='comm == "exploit_static"'
echo "$FILT" > events/kprobes/sch/filter 2>/dev/null; echo "sch comm-filter rc=$?"
echo "$FILT" > events/kprobes/tick/filter 2>/dev/null; echo "tick comm-filter rc=$?"
F1=$(cat events/kprobes/sch/filter 2>/dev/null)
if [ -z "$F1" ]; then
  LO=$((PP - 64)); [ $LO -lt 1 ] && LO=1
  HI=$((PP + 1024))
  PF="common_pid >= $LO && common_pid <= $HI"
  echo "$PF" > events/kprobes/sch/filter; echo "sch pid-filter rc=$? -> $(cat events/kprobes/sch/filter)"
  echo "$PF" > events/kprobes/tick/filter; echo "tick pid-filter rc=$? -> $(cat events/kprobes/tick/filter)"
else
  echo "sch filter = $F1"; echo "tick filter = $(cat events/kprobes/tick/filter)"
fi
echo 8192 > buffer_size_kb 2>/dev/null
killall trace_reader_bin 2>/dev/null; sleep 0.2
setsid /data/local/tmp/trace_reader_bin /data/local/tmp/depth.log </dev/null >/dev/null 2>&1 &
chmod 666 /data/local/tmp/depth.log 2>/dev/null
echo 1 > events/kprobes/enable 2>&1; echo "enable rc=$?"
echo 1 > tracing_on 2>&1; echo "tracing_on rc=$?"
touch /data/local/tmp/armed_ok
sync
echo DONE
