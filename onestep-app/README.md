# 一键GLKS — 一键临时 root（手机端，无需 PC）

APK 自带全套工具链（`exploit_static` / `kernelsu.ko` / `ksudbin` / `magiskpolicy` /
`ksu_rules` / `kmsg_dumper` / `load_ksu.sh` / `run_ksu.sh` / `stage_kit.sh`），
安装后点两次按钮即可：**启动 Shizuku → 一键越狱**。

适用：HONOR 90 Pro `REP-AN00`，MagicOS 9.0.0.230（内核 5.10.236）。
原理与移植细节见 [`../docs/REP-AN00_PORT.md`](../docs/REP-AN00_PORT.md)，
C1 fire 崩溃的根因与修复见 [`../docs/C1_FIRE_ROOTCAUSE.md`](../docs/C1_FIRE_ROOTCAUSE.md)。

## 界面

- **状态卡片**：最上方状态（待机/检查环境/启动 Shizuku/等待就绪/申请授权/执行套件/完成/失败）、
  进度条、**两行滚动任务单**（当前步骤深色大字号 + 下一步灰色小字号，推进时向上滚动）、
  **毫秒级计时**（`已用时 00:00.000`，完成/失败冻结）。
- **设备状态**：无障碍 / Shizuku / KernelSU / 可用内存 / 开机时长，每 500ms 实时刷新。
- **按钮**：可点时蓝底白字，不可点时灰底灰字；任一时刻最多一个蓝色。
  点击灰色按钮会在屏幕下部弹出白底黑字提示（具体原因 +「详情请见日志」）。
- **日志页**：完整运行日志 + 一键复制。

## 两条流程

### 启动 Shizuku（4 步）

```
检查环境（无障碍 / Shizuku） → 启动并等待 Shizuku 就绪 → 获取 Shizuku 授权 → 完成
```

无障碍服务用于在 Shizuku 界面自动点「启动」与权限弹窗「始终允许」。
MagicOS 会在重启/运行中清掉第三方无障碍服务，App 在拿到
`WRITE_SECURE_SETTINGS` 后会自愈（见下）。

### 一键越狱（8 步）

```
检查 Shizuku → 设备状态门槛 → 校验并落盘套件 → 临时 root（GhostLock，k/5）
→ 加载 KernelSU 模块 → 等待 su 可用 → 收尾（关调试、停 Shizuku）→ 完成
```

- 设备状态门槛：**开机 ≥ 60s、可用内存 ≥ 4GB**（温度/大核频率不再作为门槛）；
- 「临时 root（GhostLock）」会显示 `k/5`：`run_ksu.sh` 每 2 秒统计 `run.log` 中
  已成功的 fire 数并回显；
- 成功后自动收尾：`settings put global adb_wifi_enabled 0`（关闭无线调试）+
  停止 Shizuku 服务。

## 套件如何落盘（防 0 字节/被替换）

1. App 启动时把 APK 内置 assets **逐文件 md5 同步**到应用私有目录
   `/sdcard/Android/data/com.ghostlock.ksuapp/files/kit`，并写出 `kit.md5` 清单
   （同时生成 `kernelsu.new.ko` 别名）；
2. `stage_kit.sh` **只从该私有目录**拷贝到 `/data/local/tmp`，按清单逐文件复核
   md5，任何缺失/0 字节/被改写都会以 `KIT_BAD` 中止；
3. 公有目录（`/sdcard/Download/Shizuku`、assets 直接解压）逻辑已删除。

## 构建

依赖见 [`../docs/DEPENDENCIES.md`](../docs/DEPENDENCIES.md)。

```sh
# 1) 先构建 exploit（见仓库根目录 docker-build.sh）
(cd .. && PROJECT=honor-REP-AN00_9.0.0.230 ./exploit/docker-build.sh)

# 2) 生成 assets（exploit_static 来自上一步，其余来自 deps/）
./prepare-assets.sh

# 3) 构建 APK（ANDROID_SDK 默认 $HOME/projects/android-sdk）
ANDROID_SDK=$HOME/projects/android-sdk ./build.sh
# 产物: build/app-signed.apk
```

`deps/` 内含 KernelSU LKM（`kernelsu.ko`）、用户态（`ksudbin`）、`magiskpolicy`、
`kmsg_dumper`；`kernelsu.ko` 必须与设备内核匹配（社区构建，随仓库提供）。

## 首次使用

1. 安装 APK；
2. 开启无障碍：`设置 → 无障碍 → 一键GLKS`（或 `adb shell settings put secure
   enabled_accessibility_services com.ghostlock.ksuapp/com.ghostlock.ksuapp.ShizukuStartService`）；
3. 点「启动 Shizuku」，等状态变为「完成」；
4. 点「一键越狱」，等待「完成」（`su -c id` 应为 `uid=0 … u:r:ksu:s0`）。

## 已知限制

- Shizuku 只在进程启动/切前台时推 binder，极端情况下 App 会自我重启（最多 3 次）；
- App 进程可能被系统回收，重新打开会显示「待机」；
- 一键越狱的 fire 仍存在极小概率的残余崩溃，见 `docs/C1_FIRE_ROOTCAUSE.md` 第 7 节。
