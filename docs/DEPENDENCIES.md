# 依赖说明（编译依赖 / 一键 APK 依赖）

本仓库不含任何官方固件包或其中的系统镜像；`exploit/`、`onestep-app/`、
`research/` 只包含源码、脚本、文档与少量社区构建的二进制依赖（见文末）。

## 1. 编译 exploit（CVE-2026-43499 / GhostLock）

| 依赖 | 版本/说明 |
|---|---|
| Docker | 任意近期版本（`exploit/docker-build.sh` 会持久化一个 `debian:bookworm-slim` 容器）|
| Android NDK | r29（脚本首次运行自动下载到容器 `/opt/android-ndk`）|
| make / clang | 由 NDK 提供；容器内 apt 安装 `make curl unzip` |
| 目标配置 | `PROJECT=honor-REP-AN00_9.0.0.230`（见 `exploit/src/targets/`）|

```sh
PROJECT=honor-REP-AN00_9.0.0.230 ./exploit/docker-build.sh
# 产物: exploit/build/honor-REP-AN00_9.0.0.230/bin/exploit_static
```

## 2. KernelSU LKM（`ksu/`）

| 依赖 | 说明 |
|---|---|
| clang/LLVM | 与目标内核匹配的 AArch64 工具链 |
| 内核源码/配置 | `ksu/kernel.config`、`ksu/build_inner.sh`、`ksu/ksu_ko_build.sh` |
| 补丁 | `ksu/*.patch`（boot_id、SELinux、sepolicy 动态长度等）|

`ksu/tools/` 内是加载器源码（`load_ko.c`、`kmsg_dumper.c`）与社区构建的
用户态二进制（`ksud`、`magiskpolicy`、`kmsg_dumper`）。

## 3. 一键 APK（`onestep-app/`）

| 依赖 | 版本/说明 |
|---|---|
| JDK | 17+ |
| Android SDK | platform **android-35**、build-tools **android-15/35.0.x**（`aapt2`、`d8`、`zipalign`、`apksigner`）|
| Shizuku API | `onestep-app/libs/shizuku-{api,provider,aidl}.jar`（已随仓库提供）|
| Xposed API（仅编译期） | `onestep-app/libs/xposed-api-stub.jar`（源码在 `onestep-app/xposed-stub/`，运行期由 LSPosed 提供真实类）|
| 签名 | `onestep-app/debug.keystore`（debug 签名，仅测试用）|
| 运行期 | 设备需安装 Shizuku（v13 验证）；KernelSU 用户态 + LKM 由 App 自带资产提供；安全补丁模块还需 LSPosed（KernelSU 模块 `zygisk_lsposed`）|

```sh
cd onestep-app
./prepare-assets.sh        # 从 deps/ 与 ../exploit/build 生成 assets 二进制
ANDROID_SDK=$HOME/projects/android-sdk ./build.sh
# 产物: onestep-app/build/app-signed.apk
```

`prepare-assets.sh` 依赖：

- `../exploit/build/<PROJECT>/bin/exploit_static`（第 1 节构建）
- `onestep-app/deps/kernelsu.ko`（KernelSU LKM，需匹配设备内核；社区构建）
- `onestep-app/deps/ksudbin`（KernelSU 用户态）
- `onestep-app/deps/magiskpolicy`、`onestep-app/deps/kmsg_dumper`（与 `ksu/tools/` 一致）

## 4. 运行时（设备侧）

- HONOR 90 Pro `REP-AN00`，MagicOS 9.0.0.230，内核 `5.10.236-android12-9-g3c84ac3cc469`；
- 触发方式：`adb shell`（uid 2000）或 Shizuku 提供的 shell；
- 短链/全链路脚本见 `research/scripts/`（推送至 `/data/local/tmp` 执行）。

## 5. 不包含的内容

- 官方固件包（`boot.img` 等）及其中的系统镜像/内核镜像：官方未公开，且体积大；
  本仓库只保留由源码构建的产物与反汇编分析结论（见 `docs/REP-AN00_PORT.md`）；
- 设备日志、研究过程中的大体积二进制（`*.log`、符号化后的内核 ELF 等）。

## 4. 安全补丁模块（LSPosed）

- 载体：与一键 APK 同一个 APK（Manifest 内 `xposedmodule` 元数据 +
  `assets/xposed_init` → `com.ghostlock.ksuapp.PatchEntry`）；
- 作用域：`com.hihonor.trustspace`、`com.hihonor.securityserver`、
  `com.hihonor.parallelspace`（`com.hihonor.findmydevice` 可选）；
- 运行期依赖：LSPosed（`v2.2.1` 实测；KernelSU 模块 `zygisk_lsposed`），
  首次安装需在 LSPosed 管理器中启用模块并勾选上述作用域；
- 编译期依赖：`libs/xposed-api-stub.jar`（`compileOnly` 语义，**不**打包进 APK），
  源文件在 `xposed-stub/src/`，改 API 时执行：

```sh
cd onestep-app
javac -d xposed-stub/classes $(find xposed-stub/src -name '*.java')
(cd xposed-stub/classes && jar cf ../../libs/xposed-api-stub.jar .)
```
