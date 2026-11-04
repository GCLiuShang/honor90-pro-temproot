#!/bin/sh
# Populate app/src/main/assets with the binaries the one-tap app ships.
#
# 这些二进制不进 git（体积大 / 由源码或上游构建），统一由本脚本从仓库内
# 的位置或 deps/ 拷入 assets：
#   exploit_static   <- ../exploit/build/<PROJECT>/bin/exploit_static
#                       （先按 docs/DEPENDENCIES.md 构建）
#   kernelsu.ko      <- deps/kernelsu.ko（KernelSU LKM，见 deps/README.md）
#   kernelsu.new.ko  <- deps/kernelsu.ko 的副本（load_ksu.sh 期望的名字）
#   ksudbin          <- deps/ksudbin（KernelSU 用户态）
#   magiskpolicy     <- deps/magiskpolicy
#   kmsg_dumper      <- deps/kmsg_dumper
set -eu
cd "$(dirname "$0")"

D=app/src/main/assets
PROJECT="${PROJECT:-honor-REP-AN00_9.0.0.230}"
EXPLOIT="../exploit/build/$PROJECT/bin/exploit_static"

[ -f "$EXPLOIT" ] || { echo "missing $EXPLOIT — build the exploit first:" >&2; \
  echo "  (cd .. && PROJECT=$PROJECT ./exploit/docker-build.sh)" >&2; exit 1; }
for f in kernelsu.ko ksudbin magiskpolicy kmsg_dumper; do
  [ -f "deps/$f" ] || { echo "missing deps/$f" >&2; exit 1; }
done

cp -f "$EXPLOIT"          "$D/exploit_static"
cp -f deps/kernelsu.ko    "$D/kernelsu.ko"
cp -f deps/kernelsu.ko    "$D/kernelsu.new.ko"
cp -f deps/ksudbin        "$D/ksudbin"
cp -f deps/magiskpolicy   "$D/magiskpolicy"
cp -f deps/kmsg_dumper    "$D/kmsg_dumper"
chmod 755 "$D"/exploit_static "$D"/ksudbin "$D"/magiskpolicy "$D"/kmsg_dumper
echo "assets ready: $(md5sum "$D"/exploit_static | cut -d' ' -f1) exploit_static"
