# C1 fire 崩溃的根因分析与修复（HONOR 90 Pro / REP-AN00）

本文记录临时 root 短链在 `C1 fire`（第 1 次 fire）处约 1/3 概率硬复位的问题、
定位过程、因果实验与修复。数据来自真机（REP-AN00，MagicOS 9.0.0.230，
5.10.236-android12-9-g3c84ac3cc469）与 vendor 内核反汇编。

## 1. 现象

- 短链 6 次干净启动：4 成功 / 2 崩溃，均停在 `C1 fire`（`W3p planted` 之后无 `C2 fired-ok`）。
- 崩溃的日志序列与成功一致，差异只在时序：崩溃时 `D1` 与 `C1 fire` 两行之间
  waiter 又完成了一次种植。

## 2. 几何模型（vendor 内核反汇编 + 探针实测）

| 量 | 值 | 来源 |
|---|---|---|
| sigret 种植路径（`restore_fpsimd_context` 拷贝基址）深度 | 0x460 | 反汇编帧链 |
| ghost word0 深度（`SLIDE_SIGRET_BUF_BYTE=0x10`） | 0x450 | `pib + 0x450 == sp_rfps + 0x210` 实测 |
| ghost 区间 | `[0x408, 0x458)` | 同上 |
| fire 路径帧底（`el0_svc`→`__do_sys_sched_setattr`→`__sched_setscheduler`→`adjust_pi`→chain） | 0x360 | 反汇编 |
| IRQ/`__schedule` 路径在 waiter 栈上的最大深度 | 0x390 | kprobe `__schedule sp=$(stack)` 实测（58 次调度，危险窗口 `(0x338,0x458)` 内 0 次）|

`restore_fpsimd_context` 在拷贝前会 `memset 0x210`、再 `copy 0x200`，
覆盖 `[0x250,0x460)` —— 包含整个 ghost 区间。

## 3. 根因

fire 之前存在 **O_SYNC 写盘**（`D1` 诊断行无条件写；`C1 fire` 标记受
`WALK_TRACE=1` 门控，而 `WALK_TRACE` 被 `env_defaults.h` 默认烘焙为 1）。
本机实测一次 O_SYNC 写（进程内）> 50µs 且 < 1ms，而 waiter 种植后的用户态
hold 只有 **50µs**：

```
plant  → stamp_fresh=1 → consumer 看到 → O_SYNC 写(百µs~ms)
       → waiter hold 已超时 → 清 flag → 重新 tgkill
       → rt_sigreturn 的 memset+copy 重写 ghost 区
       → consumer 此时才 fire → 链读到撕裂的 ghost → 崩溃
```

崩溃日志里的 `D1 → W3p planted → C1 fire → W3p planted` 正是这个序列。

## 4. 因果实验（同一二进制，仅改环境变量）

| 配置 | 结果 |
|---|---|
| `SLIDE_D1_TRACE=1`（恢复 fire 前 O_SYNC 写） | **2/2 崩溃**，签名一致 |
| 无 fire 前 O_SYNC 写 | **5/5 成功**（25/25 次 fire 全 `C2 fired-ok`，0 次重种） |
| 1ms hold + `SLIDE_D1_TRACE=1` | **成功**，5/5 fire 且 0 次重种（1ms 完全吸收了该写） |
| 1ms hold、无 D1（默认配置） | 连续干净启动全部成功 |

另外：`SLIDE_SG_SELF=1`（waiter 自己 fire）实测在 fire 处崩溃。原因是
fire 必经 `security_task_setscheduler → selinux_task_setscheduler(0x20) →
avc_has_perm(0xB0)`（0x2C0→0x370，命中缓存再 +`avc_lookup` 0x40，未命中
+`avc_compute_av` 0x50 更深），会压进 ghost 区 `[0x408,0x458)` —— 自 fire
在结构上不可行，已放弃。

## 5. 修复

1. `D1` 只在 `SLIDE_D1_TRACE` 下写；`C1 fire` 标记移到 fire 返回之后；
2. `SLIDE_SIGRET_GAP_US` 默认 **50 → 1000**（1ms hold，覆盖 consumer 的调度延迟）；
3. `WALK_TRACE` 的其余 marker 保持在 plant 之前，不影响窗口。

## 6. 验证

- 短链：连续 10 次干净启动 10/10 成功（修复前 4/6）；
- 完整链路（exploit + KernelSU LKM）：5/5 成功；
- App 驱动：多次成功，8 步任务单/阶段进度正常。

## 7. 残余风险

- consumer 被抢占超过 hold（当前 1ms）仍可能让 waiter 重种；
- 软中断尾段（`irq_exit → __do_softirq → handler`）未直接采样，理论最深可接近
  0x408，建议后续用同款探针补测；
- App 运行环境（前台动画/Shizuku/无障碍）会抬高小核负载，已在 App 侧降低
  运行期 UI 开销（关闭呼吸动画、计时降频）。
