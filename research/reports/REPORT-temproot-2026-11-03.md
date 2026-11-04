# 临时 root 崩溃排查：阶段性结论与落地（2026-11-03）

承接 `.research/REPORT-crash-2026-11-02.md`，本文汇总到 2026-11-03 00:45 为止的全部成果，
并说明哪些结论已经落到脚本 / APK，哪些仍属未证实的推断。

设备：HONOR 90 Pro `REP-AN00`，MagicOS 9.0.0.230，内核 `5.10.236-android12-9-g3c84ac3cc469`。
利用链：GhostLock CVE-2026-43499（rt_mutex UAF）+ KernelSU LKM。

---

## 1. 崩溃的因果链（有真机证据）

```
fire: sched_setattr(waiter_tid) → rt_mutex_setprio / rt_mutex_adjust_prio_chain
  → 沿 ghost waiter（kstack 上被 sigret 帧覆盖的 rt_mutex_waiter）走 PI 链
  → 读到野指针（实例：0xc6b6a8400f72c280，ESR 0x96000004，level-0 translation fault, read）
  → Oops → panic_on_oops=1 → panic
  → panic=-1（panic 后不自动重启，内核停住）
  → PMIC / hypervisor 看门狗在 ~44–45 s 后硬复位
```

内核参数（真机读得）：`panic_on_oops=1`、`hung_task_panic=1`、`hung_task_timeout_secs=30`、`panic=-1`。
pstore 为空（无 ramoops），所以现场只能靠临时 root 启动 O_SYNC 的 `kmsg_dumper` 抓取。

**失败样本的另一形态**：没有 oops，fire 后系统还能继续跑约 46 s（kmsg 里其它子系统仍在打日志），
然后被看门狗复位 —— 即"持锁挂死、系统带伤运行"。

---

## 2. 植入（stamp）是成功的，失败发生在"链走"

给 sigret handler 加了两个自证标记：

- `W3p planted`：找到 FPSIMD 记录并把 10 个 waiter 字段写入 sigreturn 帧；
- `W3b ctxbail / nomagic`：没找到记录或记录链走偏（植入失败）。

真机结果：**所有失败运行的 run.log 里 `W3p` 都出现、`W3b` 一次都没出现**。
即 `SLIDE_SIGRET_BUF_BYTE=0x10` 的植入动作本身是执行的，失败不在"没植入"。

同时反汇编确认（本机 boot_a 镜像 + kallsyms）：
`restore_fpsimd_context` 帧 = `0x250`（`stp [sp,#-48]!` + `sub sp,#0x220`），缓冲 `0x210`，
与开源注释中的 stock GKI 先验一致；历史日志 `full4.log`（同型号同版本）用 `0x10` 连续 7 次 `C2 fired-ok`。

---

## 3. kprobe 看到的内核侧差别（root 条件下）

用 tracefs kprobe（无需编译内核模块）挂：

- `p:gset rt_mutex_setprio p=%x0 pi_top=%x1 pib=+0x8d0(%x0)`（`0x8d0` = `task_struct.pi_blocked_on`，由反汇编 `ldr x25,[x19,#2256]` 定位）
- `p:gchain rt_mutex_adjust_prio_chain task=%x0 pib=+0x8d0(%x0)`
- `p:sattr __arm64_sys_sched_setattr pid=+0x0(%x0)`

成功与失败的序列差别：

```
成功： sattr(pid=waiter) → gchain(task=waiter, pib=<vmap 栈地址>) → gset(p=…5100, pi_top=…5100)
      其中 p 的低 12 位 = 0x100 = fake_task = dm_base+0x100

失败： sattr(pid=waiter) → gchain(task=waiter, pib=<vmap 栈地址>) → 日志到此为止
      整份 trace 里 fake_task 一次都没出现
```

另外：`pib` 落在 `0xffffffc0…` 段（arm64 vmap 区 = 内核栈），两次样本的低 12 位都是 `0xb60`，
说明 ghost 在栈上的相对位置是稳定的。

注意：`rt_mutex_adjust_prio_chain` 这个独立符号在 fire 路径上**没有被调用**（命中数一直是 18），
fire 的链走被内联进了 `rt_mutex_setprio`；所以观测要点是 `gset`，不是 `gchain`。

---

## 4. "设备劣化"的可观测机制：热限频锁存

这是本轮最重要的发现。

| 状态 | cpu4（大核簇） | cpu7（超大核） | cpuss 温度 | 结果 |
|---|---|---|---|---|
| 断电重启后 | 2.496 GHz（满） | 2.995 GHz（满） | 27 °C | 成功（多次） |
| 重负载后 | 1.9968 | 2.1312 | 42 °C | 失败 |
| 部分恢复 | 2.2272 | 2.3616 | 33 °C | 失败 |
| 恢复到满 | 2.496（满） | 2.3616（仍限） | 30 °C | 成功 |
| 深度加热 | 1.3248（53%） | — | 68 °C | — |

要点：

- `scaling_max_freq` 会被 thermal engine 从 `cpuinfo_max_freq` 往下压，**重启后不清除**；
  只有断电重启 / 充分降温才恢复。这解释了 21:07–21:38 的 0/7 与断电重启后立刻 7/7。
- **只有大核簇（cpu4-6）恢复满频才成功**；cpu7 单核限频不影响。
- 机制（推断，见 §6）：exploit 的竞速时序常量（3 ms settle / 2000 次 yield / 50 µs 重植窗口）
  是按"shell 被限制在 0-3 小核"调出来的；本机 `Cpus_allowed_list = 0-7`，walk child 又把
  affinity 重置成全部核心，于是 owner/waiter/consumer 会落到 4-7 大核上。大核被限频后，
  这些线程的相对速度变化，时间窗错位 → PI 链状态不对 → 链走坏。

---

## 5. 已落地的改动

### 5.1 `run_ksu.sh`

1. **执行前预检（gate）**，不满足直接退出（exit 4），**不跑 exploit、设备不会重启**：
   - `cpu4/scaling_max_freq >= cpuinfo_max_freq * 98%`
   - `thermal_zone16`（cpuss）`<= 40 °C`
   - `/proc/uptime >= 170 s`
2. **`SLIDE_LITTLECORE=1`**：把竞速线程钉在 0-3 小核（与开源调参前提一致）。
3. **`SLIDE_NOKILL=1`**：失败路径不 SIGKILL 带损坏 poll_table 的 waiter。
4. **删除荣耀 STP HAL 相关逻辑**：临时 root 到 KernelSU 拉起为止，不再做任何额外操作。
5. 保留：等 KSU 模块、等 `/system/bin/su`、必要时拉起 KernelSU 装 su。

### 5.2 APK（一键GLKS）

按钮拆成三个，避免"Android + 厂商后台调度"把两件事搅在一起：

- **启动 Shizuku**：只做环境检查 → 无障碍拉起 Shizuku → 等 binder → 取授权，完成后提示
  「Shizuku 已就绪：现在可以点『一键越狱』」；不执行套件。
- **一键越狱**：要求 Shizuku 已就绪（否则直接拒绝并提示先点启动）；先做与脚本一致的设备门槛
  检查（大核满频 / 温度 / 开机时间），不通过则安全失败；通过后以 shell(2000) 跑套件并拉起 KernelSU。
- **日志**：查看/复制运行记录。

`exploit_static` 资产已替换为带 `SLIDE_LITTLECORE` 支持的构建（md5 `5da573d3…`）。

---

## 6. 证据等级与未决问题（必须如实记录）

1. **限频相关性是小样本**：有 cap 记录的失败 2 次、成功 1 次；时间、负载、boot 次数都不同，
   只能算**相关性**，不是统计上确立的因果。
2. **"3 ms / 2000 yield / 50 µs"是源码常量，不是实测时序**：我们没有在真机上量过
   owner 阻塞、requeue→tkill、stamp→fire 的真实间隔。
3. **root 观测存在偏差**：kprobe/tracefs 需要 root，而 root 只能来自"一次成功"或 agent
   （cred 之后）。能抓到失败样本的运行，必然发生在同一 boot 已成功过一次、或 KSU 已加载、
   或探针已介入的条件下 —— 这些都不等于 App 在非 root 下的真实运行。
4. **"脏状态"没有代码级证据**：没有直接看到残留 waiter / 污染的 PI 树 / 泄漏的 gl_parked；
   而且如果它是内核内存，普通重启就应清除，没有理由必须断电。断电重启的"疗效"更可能来自
   清掉热/PMIC 锁存。
5. 因此：**"大核满频 + 温度 < 40 °C + 开机 > 170 s"这套门槛是保守的工程措施**，方向有证据支持，
   但阈值（98% / 40 °C / 170 s）是经验值，尚未经过多轮对照标定。

---

## 7. 下一步（待真机）

1. 给 exploit 自测时序：在 `M5/M7/W1/W3/stamp/C1` 上加 `cntvct_el0` 时间戳，并把
   `sched_getcpu()`、`scaling_cur_freq` 写进 run.log（**不需要 root**），用失败运行的最后一段时序
   验证"时间窗错位"假说。
2. 受控 A/B：同二进制、同 boot 条件，大核满频 vs 大核限频各跑 N≥5 次（无探针），统计失败率。
3. 把阈值标定到数据上：至少覆盖 98%/95%/90% 三档频率与 38/40/42 °C 三档温度。
4. 只在一次性、明确标注的实验中用 root 侧探针（kprobe 读 `restore_fpsimd_context` 的 `sp` 与
   `pi_blocked_on` 对比），并配同条件无探针对照，量化观察者效应。

---

## 8. 套件持久化（hash-pinned 外部镜像）——已实现并验证

### 8.1 问题回顾

`/data/local/tmp` 里的套件在设备被看门狗硬复位后会变成**等长全 0 文件**
（实测 9 个文件全部如此）。根因是目标端写入未回写，不是"文件放哪"的问题。

### 8.2 设计

```
APK assets（签名保护，作为唯一可信清单）
        │
        ├── 解包到 /data/local/tmp/glkit/assets
        │
        ├── 若 /sdcard/Download/Shizuku/kit 存在且【逐文件 md5 与 assets 一致】
        │       → 采用 external 作为源（KIT_SRC=external）
        │   否则 → 回退 assets（KIT_SRC=apk）
        │
        ├── cp 到 /data/local/tmp + chmod + sync
        │
        └── 逐文件 cmp 回读 → KIT_OK / KIT_BAD（KIT_BAD 时 App 中止，不跑 exploit）
```

要点：

- 外部目录**不是任意代码入口**：公共存储可被其它应用改写，只有与 APK 内置 assets
  **逐文件一致**时才启用，因此"换掉 exploit_static 让它以 root 执行"这条路被堵住。
- 外部目录的作用是**持久、可检查的镜像**：可以在不碰 APK 的情况下核对内容；
  当 `/data/local/tmp` 被硬复位清成 0 时，可从镜像或 APK 重新落盘。
- 想真正更新套件内容，仍然要重编 APK（清单就是 assets 本身，随签名受保护）。

### 8.3 实现位置

- `ksu-app/app/src/main/assets/stage_kit.sh`：校验、复制、sync、cmp。
- `MainActivity.runKit()`：解包 assets 后调用该脚本；输出含 `KIT_SRC=` 与 `KIT_OK/KIT_BAD`；
  `KIT_BAD` 直接 `setState(S_FAIL)` 中止。

### 8.4 真机验证（shell uid 2000，非 root）

```
$ sh /data/local/tmp/glkit/assets/stage_kit.sh
KIT_SRC=external
KIT_OK
```

篡改外部镜像中的一个文件后：

```
$ echo TAMPER >> /sdcard/Download/Shizuku/kit/exploit_static
$ sh /data/local/tmp/glkit/assets/stage_kit.sh
DIFF exploit_static
KIT_SRC=apk
KIT_OK          ← 回退 APK assets，仍然成功
```

恢复该文件后重新变为 `KIT_SRC=external / KIT_OK`。

外部镜像已用当前 APK 的 10 个文件初始化：
`/sdcard/Download/Shizuku/kit/`（md5 与 assets 全部一致）。

---

## 9. 补充：三层来源的持久化设计（最终版）

第 8 节只考虑了公共目录；实际上持久化可以分三层，且**私有层更适合做主来源**：

| 层 | 路径 | 谁可写 | 角色 |
|---|---|---|---|
| private | `/sdcard/Android/data/com.ghostlock.ksuapp/files/kit` | 只有本 App | **主来源**：App 自己维护、逐文件 md5 + fsync 同步；别的应用（含公共存储权限持有者）写不了；shell(2000) 可读 |
| public | `/sdcard/Download/Shizuku/kit` | 任何有存储权限的应用/用户 | **手工备用**：APK 或 Shizuku 失效时用终端自救；因为可被改写，必须逐文件哈希一致才采用 |
| assets | APK 内置 | 只有签名者 | **可信根**：前两层都缺失或不一致时的最终来源 |

选择顺序：`private → public → assets`，每一层都必须与 assets **逐文件 md5 一致**才启用。
无论用哪层，复制到 `/data/local/tmp` 后都 `sync` 并逐文件 `cmp` 回读（`KIT_OK`/`KIT_BAD`）。

自动抢修：`MainActivity.syncPrivateKit()` 在 App 启动时（后台线程）和每次 `runKit()` 前
同步私有镜像（逐个比对 md5，变更才重写，写后 `FileDescriptor.sync()`）。

### 真机验证矩阵（shell uid 2000，非 root）

| 场景 | 输出 |
|---|---|
| 三层齐全 | `KIT_SRC=private` / `KIT_OK` |
| 隐藏 private | `KIT_SRC=public` / `KIT_OK` |
| 隐藏 private + public | `KIT_SRC=assets` / `KIT_OK` |
| 隐藏 private，且改动 public 的 `exploit_static` | `DIFF exploit_static (...)` → `KIT_SRC=assets` / `KIT_OK` |
| 恢复 | `KIT_SRC=private` / `KIT_OK` |

公共目录另附 `README.txt`：说明它只是手工备用、会被哈希校验、以及从终端手工
`cp → chmod → sync → sh run_ksu.sh` 的完整步骤（`run_ksu.sh` 本身不需要无障碍）。
