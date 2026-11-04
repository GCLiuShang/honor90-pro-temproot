# HONOR 90 Pro (REP-AN00) 移植说明

设备：HONOR 90 Pro `REP-AN00`，MagicOS 9.0.0.230 SP1，Android 15，
内核 `5.10.236-android12-9-g3c84ac3cc469`（Qualcomm taro / SM8450）。

> 官方固件包与其中的 `boot.img`/内核镜像**不随本仓库分发**；本文只记录
> 分析结论与复现方法。

## 1. 符号与结构体偏移的来源

`exploit/src/targets/honor-REP-AN00_9.0.0.230/target.h` 中每个值都标注了
来源（`[kallsyms]` / `[disasm]` / `[image]`）：

- 从官方 SD 卡整包内的 `boot.img` 提取内核 Image（boot header v4，页偏移 4096）；
- 用 `vmlinux-to-elf` 从 Image 的 kallsyms 恢复符号（约 14 万符号，基址
  `0xffffffc008000000`）；
- 用反汇编确认 task_struct 字段（本机 `cred` 在 `0x7b8`，比 stock GKI 的
  `0x780` 大 `0x38`；`pi_lock 0x8b4`、`pi_waiters 0x8b8`、`pi_blocked_on 0x8d0`、
  `pi_top_task 0x8c8`、`prio 0x84`、`normal_prio 0x8c`、`sched_class 0x98`）；
- `rt_sched_class` 位于 `0x23449e0`（与 AGT-AN00 9.0.0.230 相差 `+0x200`）。

## 2. 载体与几何

- 载体：`rt_sigreturn → restore_fpsimd_context`（512 字节 fpsimd 拷贝）；
- 拷贝基址深度 **0x460**；`SLIDE_SIGRET_BUF_BYTE=0x10` 时 ghost word0 落在
  深度 **0x450**，ghost 区间 `[0x408,0x458)`；
- 该值由真机探针交叉验证：`pib + 0x450 == sp_rfps + 0x210`。

## 3. 关键修复（相对上游）

- `slide.c`：`D1` 诊断写改为仅 `SLIDE_D1_TRACE` 下执行；`C1 fire` 标记移到
  fire 返回后；`SLIDE_SIGRET_GAP_US` 默认 **1000µs**；
- 新增 `SLIDE_SG_SELF`（自 fire 实验开关，默认关闭；实验结论见
  `docs/C1_FIRE_ROOTCAUSE.md`）；
- 新增 `honor-REP-AN00_9.0.0.230-mcast`（mcast 载体实验目标，默认不启用）；
- 新增 `perf_test.c`、`trace_reader.c` 等诊断工具。

## 4. 测试脚本

`research/scripts/` 内为真机脚本（推送到 `/data/local/tmp` 执行）：

| 脚本 | 用途 |
|---|---|
| `run_dump.sh` | 短链 + permissive + 日志落盘 |
| `run_ksu_fast.sh` | 完整链路（快速门控：开机≥60s、内存≥4GB、温度≤60°C）|
| `run_ps.sh` + `arm18.sh` | 探针 + 短链（pi/rfps 读数）|
| `run_depth.sh` + `arm_depth2.sh` | 抢占/调度深度测量 |
| `run_self.sh` | sigret 自 fire 实验 |
| `run_d1.sh` | fire 前 O_SYNC 写的 A/B 对照 |
