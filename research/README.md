# research —— C1 fire 崩溃的定位材料

本目录是 `C1 fire` 崩溃（约 1/3 概率硬复位）调查过程中的可复现材料，
结论见 [`../docs/C1_FIRE_ROOTCAUSE.md`](../docs/C1_FIRE_ROOTCAUSE.md)。

## 内容

```
reports/    真机实验报告（崩溃、模块、临时 root）
harness/    离线模型（rtmutex / PI 链 / 红黑树 / 写原语）
scripts/    真机实验脚本（推送至 /data/local/tmp）
```

### harness（纯用户态，无设备依赖）

从内核行为抽取的最小模型，用于判断"某种 ghost 内容/覆写会安全 bail 还是崩溃"：

| 文件 | 作用 |
|---|---|
| `rtmutex_model.c` | rt_mutex 结构/队列模型 |
| `chain_model.c` | `rt_mutex_adjust_prio_chain` 的帧与检查顺序 |
| `enumerate.c` | 枚举载荷字段组合 |
| `requeue.c` | EDEADLK requeue 的 waiter 移动 |
| `write_trace.c` | 任意写原语（pi_tree erase）的轨迹 |
| `pitree.c` | pi_tree 红黑树操作 |

构建：`cc -O2 -o chain_model chain_model.c`（其余同理）。

### scripts（真机）

| 脚本 | 用途 |
|---|---|
| `run_dump.sh` | 短链：hijack→anchor→cred→permissive，并落盘日志 |
| `run_ksu_fast.sh` | 完整链路（exploit + KernelSU LKM），快速门控 |
| `arm18.sh` / `run_ps.sh` | arm `pi`/`rfps` 探针后跑短链，读 ghost 内容 |
| `arm_depth2.sh` / `run_depth.sh` | arm `__schedule`/`scheduler_tick` 探针，测抢占深度 |
| `run_self.sh` | waiter 自 fire（`SLIDE_SG_SELF=1`）实验 |
| `run_d1.sh` | fire 前 O_SYNC 写的 A/B 对照（`SLIDE_D1_TRACE=1`）|
| `dump_logs.sh` | 设备侧日志收集（agent）|

使用前提：设备已 root（短链或 KernelSU），脚本内路径为 `/data/local/tmp`。

## 关键数据

- ghost 区间 `[0x408,0x458)`（word0 深度 0x450），fire 路径帧底 0x360；
- IRQ/`__schedule` 在 waiter 栈上的最大深度 0x390（58 次采样，危险窗口内 0 次）；
- fire 前 O_SYNC 写（进程内）> 50µs 且 < 1ms；1ms hold 可完全吸收。
