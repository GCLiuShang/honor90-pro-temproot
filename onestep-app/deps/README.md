# deps —— 一键 APK 的二进制依赖（不进 assets，构建时由 prepare-assets.sh 拷入）

| 文件 | 来源/说明 |
|---|---|
| `kernelsu.ko` | KernelSU LKM，针对本机内核 `5.10.236-android12-9-g3c84ac3cc469` 构建（`ksu/` 内有构建脚本与补丁）。必须与设备内核匹配，否则 insmod 会失败/拒绝。 |
| `ksudbin` | KernelSU 用户态（KernelSU 发行版的 `ksud`），App 将其安装为 `/data/adb/ksu/bin/su` 与 `/data/adb/ksud`。 |
| `magiskpolicy` | Magisk 的 `magiskpolicy`（sepolicy 注入，`ksu/ksu_rules`）。与 `ksu/tools/magiskpolicy` 相同。 |
| `kmsg_dumper` | 小工具：把 `/dev/kmsg` 持续写入文件，用于硬复位前的内核日志取证。源码见 `ksu/tools/kmsg_dumper.c`。 |

说明：

- 这些二进制均为社区/开源构建（KernelSU、Magisk），随仓库提供以便一键复现；
  需要自行构建时，用 `ksu/` 下的脚本与上游 KernelSU 源码。
- `debug.keystore` 仅用于本仓库的 debug 签名（口令 `android`），**不要用于正式分发**。
- 官方固件/系统镜像不在此列（见 `docs/DEPENDENCIES.md` 第 5 节）。
