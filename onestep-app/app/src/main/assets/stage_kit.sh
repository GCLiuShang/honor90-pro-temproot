#!/system/bin/sh
# Stage the APK-private kit into /data/local/tmp.
#
# 唯一来源：App 私有目录（/sdcard/Android/data/<pkg>/files/kit），该目录由
# APK 在启动时从 assets 逐文件 md5 同步并写出 kit.md5 清单；公有目录
# （/sdcard/Download、/sdcard/Shizuku）的逻辑已删除。
#
# 落盘后逐文件按清单复核 md5，任何缺失/0 字节/被改写都会以 KIT_BAD 中止
# （硬复位曾把 /data/local/tmp 里的文件清成 0 字节）。
D=/data/local/tmp
KIT="${1:-/sdcard/Android/data/com.ghostlock.ksuapp/files/kit}"
MAN="$KIT/kit.md5"

if [ ! -f "$MAN" ]; then
  echo "KIT_BAD (no manifest: $MAN)"
  exit 1
fi

fail=0
while read -r sum name; do
  [ -z "$name" ] && continue
  src="$KIT/$name"
  if [ ! -f "$src" ]; then
    echo "MISS $name"
    fail=1
    continue
  fi
  cp -f "$src" "$D/$name" || { echo "CPFAIL $name"; fail=1; continue; }
  got=$(md5sum "$D/$name" 2>/dev/null | cut -d' ' -f1)
  if [ "$got" != "$sum" ]; then
    echo "BAD $name got=$got want=$sum"
    fail=1
  fi
done < "$MAN"

sync
if [ $fail -ne 0 ]; then
  echo KIT_BAD
  exit 1
fi

chmod 755 $D/exploit_static $D/run_ksu.sh $D/load_ksu.sh $D/ksudbin \
          $D/magiskpolicy $D/kmsg_dumper 2>/dev/null
chmod 644 $D/kernelsu.ko $D/ksu_rules $D/kernelsu.new.ko 2>/dev/null
echo KIT_OK
