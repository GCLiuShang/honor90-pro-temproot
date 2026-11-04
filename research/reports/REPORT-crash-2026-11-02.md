# 临时 root 失败即重启：崩溃点定位与规避分析

日期：2026-11-02 18:30 CST
设备：REP-AN00 / MagicOS 9.0.0.230 / 5.10.236 / KernelSU 3.3.0（LKM）
结论依据：设备上 `/data/local/tmp/run.log`（17:41 最后一次尝试）、`gl_route_marks`、`gl_trace.log`、
`persist.sys.boot.reason.history`、以及 `honor90-ghostlock` 源码。

---

## 1. 结论

**不是"载体随机不稳"，失败点可以精确定位到某一次 `sched_setattr`（日志里的 `C1 fire`）。**
这个调用是整套利用的任意写原语：它让内核沿一条被 UAF 的 rt_mutex PI 链走，在持着
IRQ-off 自旋锁（`lock->wait_lock`）的状态下完成 `rb_erase`，把 8 字节写到任意地址。

链走一旦没有按预期几何落地，就会在持锁上下文里死循环或解引用坏指针，于是：

- 该 CPU 卡死 → 其它 CPU 抢同一把锁也卡死 → **看门狗在固定时间后硬复位**；
- 或者子进程被清理逻辑杀掉，唤醒被污染的 poll/rtmutex 状态 → panic。

两次失败到重启的间隔高度一致，说明是固定超时，不是随机崩溃：

| 事件 | 时间 | 距上次活动 |
|---|---|---|
| 17:23 那次 anchor 启动 | 17:23:35.5 | — |
| 重启（bootstat：`reboot`） | 17:24:20 | **44.5 s** |
| 17:41 那次 fire | 17:41:21.0 | — |
| 重启（bootstat：`reboot`） | 17:42:05 | **44.0 s** |

44 s ≈ 内核里"轮询 40 s 后清理"的路径（`sysctl_do_walk` 的 80 × 500 ms）加上 4 s 的复位时间，
也 ≈ 硬件看门狗的典型上限。两者都指向"内核在 fire 里没了"。

---

## 2. 17:41 那次到底死在哪

`run.log` 最后 8 行：

```
W1 requeue-done
W2 chain-unlocked
O3 chain-locked
[-] slide sigret: payload at buf byte 0x10 (waiter word 2) [override]
W3 sigret-enter
C1 fire          <-- 最后一行，永远不会出现 C2 fired-ok
```

对照 `gl_trace.log`（同一时刻的另一份 trace）也是停在 `C1 fire`。

`C1/C2` 是 `sched_consumer_thread()` 里的两行标记（`exploit/src/slide.c:760-764`）：

```c
wm("C1 fire\n");
long ret = sched_setattr_tid(tid, (calls % 19) + 1);
wm(ret == 0 ? "C2 fired-ok\n" : "C2 fired-err\n");
```

`C1` 之后没有 `C2`，意味着 **`sched_setattr` 这个系统调用没有返回**。它不是"返回错误"，
是内核在那条 syscall 里再没出来。

同一次 run 的前半段是成功的：

```
[+] sysctl walk hijack attempt=1 ... landed attempt=1     <- 第 1 次 fire 成功
[*] ks live probe: anchor=22158 mm=ffffff89c4869c00        <- KernelSnitch 找到 anchor
[*] dm: settling 3000ms after the mm scan (try 1)
[*] sysctl walk owner attempt=1 base=... value=... target=...
...
C1 fire                                                    <- 第 2 次 fire 挂了
```

也就是说：**彩票抽中了第一次，死在第二次。** 这一次运行里后续还有 cred / verify / selinux /
sig_enforce 四次 fire，一次都没轮到。

---

## 3. 为什么会"一次 fire 就整机没了"

`fire` 的时序握手（源码注释）：

1. waiter 线程被 `FUTEX_CMP_REQUEUE_PI` 以 EDEADLK 方式 requeue，留下一个**已释放的
   `rt_mutex_waiter`（ghost）**；
2. waiter 随后经由 `rt_sigreturn` 返回，内核把 512 字节的 fpsimd 恢复帧拷到它的内核栈上，
   利用这个拷贝把 10 个 waiter 字段（`pi_tree.parent/right/left`、`task`、`lock`、`prio`…）
   **写进那块已被释放、又落回该 kstack 的内存**；
3. consumer 线程在 `slide_stamp_fresh` 握手完成后立刻 `sched_setattr(waiter_tid)`，触发
   `rt_mutex_adjust_prio_chain()` 沿 ghost 走，最终 `rb_erase` 把 `sysctl_w_value` 写进
   `sysctl_w_target`。

这条链走全程持有 `lock->wait_lock`（raw spinlock，关中断）。任何一个字段没落到预期位置，
链就会读到真实但无关/已释放的数据 —— 在关中断持锁的状态下，结果只有两种：死循环，或访问错误地址。
两种都会让这台 CPU 再也回不来，进而拖死同锁的其它 CPU。

第 2 步的落点是**随内核构建而变的常数**，源码自己写得很清楚（`exploit/src/slide.c:406-415`）：

> `0xf0` 来自 stock GKI 14427738 的帧链（0x50+0x50+0x60+0x250）；我们自己 5.10.236 的构建是
> `0x220`，即 0x30 之差；**REP-AN00 是 vendor 构建（CFI+LTO+vendor trace hooks），两者都不匹配，
> 落点必须 sweep，不能假设。**

我们目前用的是 `SLIDE_SIGRET_BUF_BYTE=0x10`（日志里标 `[override]`）。它在 17:41 那次
第一次 fire 上是**成功**的（hijack landed），但它是否对**每一次**都成立，没有做过系统 sweep。

### 作者已经记录过的三种"必重启"模式

| # | 模式 | 触发条件 | 现状 |
|---|---|---|---|
| 1 | EDEADLK 之后 futex **超时唤醒** → `poll_freewait` 拆坏链 → panic | waiter 设置了超时 | 已规避：`SLIDE_WAIT_SECS=0`（block forever）+ `SLIDE_SIGNAL_WAKE=1`（SIGUSR1 显式唤醒） |
| 2 | `selinux_state.initialized` 被偶数字节清掉 → 所有 SID 变 unlabeled → zygote 死 → **2 s 后 watchdog 复位**（源码称 "random post-load reboot"） | 写 selinux 时 dm base bit16=0 | 已规避：`prepare_dm_page` 退休偶数页，最多重试 16 轮（仅 `W_VALUE_DM` 时生效） |
| 3 | 落地失败留下被污染的 `poll_table_entry`，一旦被唤醒/杀死 → `list_del` panic | **任何 kill 行为** | **未规避 —— 见下** |

---

## 4. 我们自己的清理逻辑在自杀（重点）

`exploit/src/sysctl.c:675-698`：

```c
// Kill+reap every failed attempt: a missed race leaves the child in the
// futex wait, and piled-up waiter threads starve the parked anchor's
// scheduler share so it flips only AFTER the route stopped polling
// (observed on device).
if (!getenv("SLIDE_NOKILL") && waitpid(child, NULL, WNOHANG) == 0) {
    kill(child, SIGKILL);
    ...
}
```

而 `exploit/src/slide.c:1028-1031` 写的是：

```c
// Cloak BEFORE the waiter clone (NEVER-kill invariant): on a landing miss
// the child lingers forever with a walk-corrupted poll_table_entry on its
// kstack; a `pkill -f 'gl_ex[p]loit'` SIGKILL would wake it and
// poll_freewait's list_del on the corrupted node panics the kernel.
```

**同一个程序里，一处写"永远不要杀"，另一处在失败路径上直接 `SIGKILL`。**

时间上也对得上：`sysctl_do_walk` 失败时会轮询 `80 × 500 ms = 40 s`，然后走到上面这段 kill；
从 fire 到重启恰好 44 s。

更糟的是我们套件（`run_ksu.sh`）把两个**诊断开关**当生产配置在用：

| 环境变量 | 现状 | 代码里的定性 | 建议 |
|---|---|---|---|
| `SLIDE_NOKILL` | **未设**（=会被 kill） | 唯一的"不要杀"开关 | **加上 `=1`** |
| `SLIDE_NO_RESTORE` | `=1` | "diagnostic … ctl.data left hijacked; **REBOOT REQUIRED**" | 去掉（失败时让它尝试还原） |
| `SLIDE_SELFCONTAINED` | `=1` | "Diagnostic: chain walk must not touch a single kernel symbol" | 保留（这是安全侧：让链只在自己页里走），但要知道它不是真机验证过的默认组合 |
| `SYSCTL_WALK_ATTEMPTS` | `=1` | 源码注释：hijack 是 "**~1.5%/attempt**" 的彩票 | 修好 kill 之前不要提高 |
| `KS_MAX_TRIES` | `=1` | — | 同上 |
| `SLIDE_SIGRET_BUF_BYTE` | `=0x10` | 必须 sweep 标定的落点 | 值得做一次系统 sweep |

当前一次成功的跑法需要 **6 次 fire**：hijack → owner → verify → cred → selinux → sig_enforce
（`NO_RSCAN=1` 已经省掉 2 次）。每一次都是独立的失败/挂死机会。

---

## 5. 规避方案（按性价比）

### A. 立刻可做，不用重编（改 `run_ksu.sh` 的环境变量）

```
SLIDE_NOKILL=1        # 失败时不再 SIGKILL 掉被污染的 waiter
```
- 作用：直接消掉上面第 3 类"自杀式 panic"。
- 代价：失败后设备上会留下挂着 `gl_parked` 的进程（每个占 16 KB 内核栈，睡死不耗 CPU）。
  它们必须等到一次真正的重启才会消失；攒多了会影响后续成功率，攒 2~3 个就手动重启清理。
- 这条改完就能做 A/B 验证：**如果失败后不再重启，说明命中了第 3 类；如果仍然重启，说明是第 1 类（fire 本身死循环）**。

```
# 去掉 SLIDE_NO_RESTORE=1  → 失败路径会尝试把 ctl.data 还原
```
- 代价：失败时会多一次 fire（还原也是一次链走）。

### B. 降低单次 fire 的死亡率

1. **跑的时候让机器闲着**：关后台、别亮屏操作、别同时开 KSU 管理器。fire 依赖 ~3 ms 的
   settle 和 stamp 对齐，负载 10+ 时线程会被抢走。
2. **一次只跑一次**，失败后等 60 s 确认没有重启再决定下一步（避免在脏状态上叠第二次）。
3. 屏幕常亮、关闭自动旋转等（减少调度抖动）。

### C. 需要改代码/重编

1. **减少 fire 次数**：verify（验证 anchor 的 comm）可以用别的方式确认；selinux 的
   `enforcing=0` 可以尝试用 `magiskpolicy --apply --live` 注入替代（省 1 次 fire）。
2. **把最后几次写搬进 KSU .ko**：.ko 已经负责从内核侧还原 `boot_id`（`bootid_ctl/buf` 参数），
   同理可以把 sig_enforce/SELinux 的收尾也放进去，减少用户态 fire。
3. **做 `SLIDE_SIGRET_BUF_BYTE` sweep**：用 `SYSCTL_W_TARGET_OVERRIDE=page`（写到自己的 dm 页）
   逐个偏移跑，统计哪个落点能稳定 "landed"，再把结果固化。

---

## 6. 建议的下一步实验

一次带观测的尝试（会重启的风险很低，因为只改 kill 行为）：

1. 主机侧开两个采集：
   - `adb logcat -b all -v threadtime > capture.log`（无线 adb 也连上了）
   - `while :; do cat /proc/uptime; sleep 0.5; done > uptime.log`（判定内核复位 vs 框架重启）
2. 设备上把 `/data/local/tmp/run_ksu.sh` 的环境加上 `SLIDE_NOKILL=1`，跑一次。
3. 判定：
   - **失败但不重启** → 确认是第 3 类，方案 A 生效，可以继续提高成功率。
   - **失败后仍 44 s 重启** → 是 fire 本身在链走里死循环，只能从落点/时序/减少 fire 次数下手。
