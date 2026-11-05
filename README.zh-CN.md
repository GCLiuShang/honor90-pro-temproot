[English](README.md) | 中文

# 荣耀 90 Pro（REP-AN00）临时 root —— GhostLock + KernelSU + 一键 App

把 **GhostLock** 利用链（CVE-2026-43499）移植到 **荣耀 90 Pro `REP-AN00`**
（MagicOS 9.0.0.230，内核 `5.10.236-android12-9-g3c84ac3cc469`）的已验证版本，
并附带一键 Android App，以及对 `C1 fire` 崩溃（曾约 1/3 概率硬复位）的根因分析。

> **Fork 谱系**：
> [yakidango-official/GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT)
> （荣耀 80 GT 原版 PoC）→ [cyrrp/honor90-ghostlock](https://github.com/cyrrp/honor90-ghostlock)
> （荣耀 90 REA-NX9 移植）→ **本 fork**（荣耀 90 Pro REP-AN00 移植、fire 时序修复、
> 一键 App、C1 fire 分析）。

> **上游支持范围**（本 fork 未改动）：基座仓库还包含荣耀 80 GT（AGT-AN00，
> MagicOS 8.0.0.128 … 9.0.0.230）与荣耀 90（REA-NX9，9.0.0.211）目标，见
> [cyrrp/honor90-ghostlock](https://github.com/cyrrp/honor90-ghostlock)。

---

## 1. 利用原理（一页说明）

CVE-2026-43499 是内核 `rtmutex` waiter 处理中的 use-after-free，利用链把它变成任意写：

1. **幽灵 waiter**：`FUTEX_WAIT_REQUEUE_PI`/`FUTEX_LOCK_PI` 组合以 `EDEADLK`
   结束；waiter 的 `rt_mutex_waiter` 已出队，但 `task->pi_blocked_on` 仍指向它。
   这个陈旧 waiter 就落在 waiter 线程自己的内核栈上（即 "ghost"）。
2. **种植**：`rt_sigreturn` → `restore_fpsimd_context` 把 512 字节 fpsimd 状态从
   用户 sigframe 拷到该栈最深处，用伪造的 `tree_entry` / `lock` / `prio`
   覆盖 ghost 的字段。
3. **点火**：`sched_setattr(waiter_tid)` → `__sched_setscheduler` →
   `rt_mutex_adjust_pi` → `rt_mutex_adjust_prio_chain` 沿伪造的树走，
   `pi_tree` 的 erase 就是真正的任意写（`*pi_left = pi_pc`）。
4. **sysctl walk**：五个阶段复用该原语：劫持 `nf_loggers` → 读回 KASLR slide →
   把 `init_cred` 写进 anchor 任务 → SELinux 转宽容 → 跳过反 root（rscan）标记。
5. **KernelSU**：借临时 root 加载 KernelSU LKM，暴露 `su`。

全程以 `shell`（uid 2000）身份、通过 Shizuku 执行，无需 PC。

---

## 2. 我们做了什么（实验记录）

### 2.1 移植到 REP-AN00

官方固件包及其中提取的 `boot.img`/内核镜像**不随本仓库分发**。我们从设备自身
内核用 `vmlinux-to-elf` 恢复符号表（约 14 万符号，基址 `0xffffffc008000000`），
并用反汇编确认每个结构体偏移：

- `task_struct`：`real_cred 0x7b0`、`cred 0x7b8`（stock GKI 是 `0x780`，本厂商
  构建大 `0x38`）、`pi_lock 0x8b4`、`pi_waiters 0x8b8`、`pi_top_task 0x8c8`、
  `prio 0x84`、`normal_prio 0x8c`、`sched_class 0x98`；
- `mm_struct` 的 slab stride 是 `0x400`（不是继承来的 `0x3c0`）：用错时
  KernelSnitch 3/3 失败，用 `0x400` 第一次就找到 anchor；
- `rt_sched_class` 在 `0x23449e0`；
- sigret 载体的落点字节在真机上扫描确定（`SLIDE_SIGRET_BUF_BYTE=0x10`）。

完整来源见 [`docs/REP-AN00_PORT.md`](docs/REP-AN00_PORT.md)。

### 2.2 基线与 `C1 fire` 崩溃

短链（拿到 root → permissive → 收集日志）6 次干净启动：**4 成功 / 2 硬复位**。
两次失败都停在**第 1 次 fire**（打印了 `W3p planted`，没有 `C2 fired-ok`），
说明崩溃在 fire，而不是后面的 KernelSU 阶段。

### 2.3 加探针

为了看到内核真实读到的内容，我们通过 `tracefs` 挂 kprobe：

| 探针 | 函数 | 作用 |
|---|---|---|
| `pi` | `rt_mutex_adjust_pi` | `task->pi_blocked_on`（ghost 指针）及其前几个字 |
| `rfps` | `restore_fpsimd_context` | 种植时 waiter 栈的参考点 |
| `sch` | `__schedule` | IRQ/调度路径在该栈上的最深栈深 |
| `tick` | `scheduler_tick` | tick 路径栈深 |
| `kmsg` | `/dev/kmsg` 落盘 | 硬复位后仍能保留的内核日志 |

配合周期计数器轨迹（`D1`）与 `O_SYNC` 持久化日志，得到下面的几何数据。

### 2.4 `C1 fire` 崩溃的根因

fire 必须发生在 ghost **仍驻留且未被改动**的窗口里。waiter 通过一段有界的
用户态自旋（hold）维持种植；窗口内任何跑在 waiter 内核栈上的东西都可能破坏
ghost——而最危险的就是种植路径自身：它先 `memset` 0x210 字节、再拷贝 0x200
字节到 ghost 区（按深度是 `[0x250,0x460)`），所以第二轮 `tgkill`/`rt_sigreturn`
会重写第一轮种下的内容。

本机实测值：

| 量 | 值 |
|---|---|
| 种植拷贝基址深度 | `0x460` |
| ghost word0 深度（落点字节 `0x10`） | `0x450` |
| ghost 区间 | `[0x408, 0x458)` |
| fire 路径帧底 | `0x360` |
| waiter 栈上 IRQ/`__schedule` 最深帧底 | `0x390`（58 次采样；ghost 窗口 `(0x338,0x458)` 内 **0** 次）|
| 进程内 `O_SYNC` 写 | **> 50 µs 且 < 1 ms** |
| 旧的用户态 hold | **50 µs** |

原代码在"看到种植新鲜"之后、调用 `sched_setattr` 之前写了两次 `O_SYNC` 诊断：

- `D1` 计时行（无条件）；
- `C1 fire` 标记（受 `WALK_TRACE` 门控，而 `env_defaults.h` 默认烘焙了
  `WALK_TRACE=1`）。

任一次写都会阻塞超过 50µs 的 hold，于是 waiter 放弃、清 flag、开始下一轮；
fire 落在 restore 中间，读到撕裂的 ghost。崩溃日志的签名正是：

```
W3p planted
D1 ... dt=95 cyc            ← 握手本身是好的
W3p planted                 ← waiter 重新种植：ghost 被重写
(没有 C2 fired-ok)          ← fire 崩溃
```

### 2.5 因果 A/B（同一二进制，仅改环境变量）

| 配置 | 结果 |
|---|---|
| `SLIDE_D1_TRACE=1`（恢复 fire 前的 `O_SYNC` 写） | **2/2 崩溃**，签名一致 |
| 无 fire 前 `O_SYNC` 写 | **5/5 成功**，25/25 次 fire `C2 fired-ok`，**0** 次重种 |
| 1ms hold + `SLIDE_D1_TRACE=1` | **成功**，5/5 fire，**0** 次重种——1ms 完全吸收了该写 |
| 1ms hold、无 trace（默认） | 连续干净启动全部成功 |

### 2.6 为什么自 fire 不行

让 waiter 对自己调用 `sched_setattr` 可以消掉跨线程窗口，但 fire 的 syscall
会复用**同一张内核栈**，而它自己的路径太深：

- fire 帧底在 `0x360`，但 SELinux hook 在 `rt_mutex_adjust_pi` **之前**执行：
  `security_task_setscheduler 0x30` → `selinux_task_setscheduler 0x20` →
  `avc_has_perm 0xB0`（命中缓存再 +`avc_lookup 0x40`；未命中再加
  `avc_compute_av 0x50` 与更深调用），可以到 `0x3A0 … 0x400+`——正好压进
  ghost 区间；
- 实测自 fire 版本同样在 fire 处崩溃，所以只保留为实验开关
  （`SLIDE_SG_SELF`，默认关闭）。

### 2.7 修复与验证

- plant 握手到 fire 之间不再有任何 `O_SYNC` 写（`C1` 标记移到 fire 之后，
  `D1` 放到 `SLIDE_D1_TRACE` 后面）；
- `SLIDE_SIGRET_GAP_US` 默认 **50 → 1000**（1ms hold）；
- 其余（KernelSU 加载、App 流程）不变。

验证：短链 **10/10** 次干净启动（修复前 4/6）；完整链路（exploit + KernelSU
LKM）**5/5**；一键 App 也端到端跑通。完整数据与残余风险（consumer 被抢占超过
1ms、软中断尾段、App 运行期负载）见
[`docs/C1_FIRE_ROOTCAUSE.md`](docs/C1_FIRE_ROOTCAUSE.md)。

### 2.8 一键 App

`onestep-app/` 把整条链封装成普通 Android 应用：

- **两个按钮**：*启动 Shizuku*（用无障碍服务自动点"启动"和权限弹窗）与
  *一键越狱*（通过 Shizuku shell 跑套件）；
- **8 步任务单**：两行滚动显示、毫秒计时，五个 sysctl 阶段运行时显示 `k/5`；
- **设备门槛**：开机 ≥ 60s 且可用内存 ≥ 4GB（不再用温度/大核频率做门槛）；
- **防篡改套件**：assets 逐文件 md5 镜像到应用私有目录并写清单，
  `stage_kit.sh` 只从那里拷贝、逐文件复核，不一致直接 `KIT_BAD`；
- **收尾**：成功后关闭无线调试并停止 Shizuku；
- 实时设备状态（无障碍 / Shizuku / KernelSU / 内存 / 开机时长），灰色按钮
  点击时底部白底黑字提示原因。

### 2.9 平行空间 / 支付保护中心 / 查找设备：LSPosed 安全补丁模块

移植临时 root 的过程中，我们还梳理了三个 MagicOS 安全功能的 root 门槛，
并把绕过做成了随同一键包 APK 分发的 LSPosed 模块。

| 组件 | root 门槛 | 绕过 |
|---|---|---|
| 平行空间（`com.hihonor.parallelspace`） | `ApplicationUtil.isRoot()`（读 `msc.parallelspace.debug_mode`）；设置空间密码时的 AssetManager **写**被 `com.hihonor.securityserver` 的 `HwKeystoreServiceImpl.isRoot()` 拒绝（`-319`） | hook `ApplicationUtil.isRoot()` → false，**并**在 root 后 `setprop msc.parallelspace.debug_mode 1`（双保险） |
| 支付保护中心（`com.hihonor.trustspace`） | 风险扫描 `getRootStatusSync() > 0 ? 0 : 6`（0 = 风险） | hook `HwSecurityDiagnoseManager.getRootStatusSync()` → 0 |
| 空间密码写入（`com.hihonor.securityserver`） | `HwKeystoreServiceImpl.isRoot()` | hook → false（仅创建/重设空间密码时需要） |

查找设备（`com.hihonor.findmydevice`）**不用** root 做 UI 闸门（`SystemUtil.isRoot()`
只用于 BI/云端上报）；之前杀 `hwsecurity` HAL 的做法会破坏查找设备，模块**不碰 HAL**。

APK 同时是一键 App 和 LSPosed 模块（legacy Xposed API，`assets/xposed_init` →
`com.ghostlock.ksuapp.PatchEntry`，作用域在 `res/values/arrays.xml`）。
安装后在 LSPosed 管理器中启用 **一键GLKS** 并勾选 `trustspace`、`securityserver`、
`parallelspace`（`findmydevice` 可选）；App 内新增的 **安全补丁模块** 页面会显示
模块是否启用、作用域是否齐全（读取 LSPosed 的 `modules_config.db`），并提供
每个 hook 的开关（默认开）与“重启服务”按钮（force-stop 目标进程让 LSPosed 重新注入）。

细节与证据：[`docs/PARALLEL_SPACE.md`](docs/PARALLEL_SPACE.md)、
[`docs/SECURITY_PATCH.md`](docs/SECURITY_PATCH.md)、
[`docs/SECURITY_PATCH_MODULE_DESIGN.md`](docs/SECURITY_PATCH_MODULE_DESIGN.md)。

---

## 3. 相对上游荣耀 90 移植的改动

| 路径 | 改动 |
|---|---|
| `exploit/src/targets/honor-REP-AN00_9.0.0.230/` | **新增目标**（偏移从设备内核逆向；来源写在文件头注释）|
| `exploit/src/targets/honor-REP-AN00_9.0.0.230-mcast/` | 实验性 mcast 载体变体（默认不启用）|
| `exploit/src/slide.c` | fire 时序：1ms hold（`SLIDE_SIGRET_GAP_US` 50→1000）、fire 前无 `O_SYNC`、`D1` 受 `SLIDE_D1_TRACE` 门控、`C1` 标记移到 fire 之后、`SLIDE_SG_SELF` 自 fire 实验 |
| `exploit/src/common.h` | `MM_STRUCT_SZ` `0x3c0` → `0x400`（REP-AN00 slab stride）、self-contained/walk 目标开关 |
| `exploit/src/sysctl.c` | 诊断用的 walk 目标覆盖与 self-contained 链模式 |
| `exploit/docker-build.sh` | 持久化构建容器的代理环境 |
| `ksu/ksu_ko_build.sh` | KernelSU LKM 构建调整 |
| `onestep-app/` | **新增**：一键 Android App（Java，无 AndroidX）、构建脚本、二进制依赖 |
| `research/` | **新增**：离线 rtmutex/PI 链模型、真机探针脚本、报告 |
| `docs/C1_FIRE_ROOTCAUSE.md`、`docs/REP-AN00_PORT.md`、`docs/DEPENDENCIES.md` | **新增** |
| `onestep-app/.../PatchEntry.java`、`PatchSettingsActivity.java`、`res/values/arrays.xml`、`assets/xposed_init`、`libs/xposed-api-stub.jar`、`xposed-stub/` | **新增**：LSPosed 安全补丁模块（平行空间/支付保护/空间密码的 root 门槛）及其设置页 |
| `docs/PARALLEL_SPACE.md`、`docs/SECURITY_PATCH.md`、`docs/SECURITY_PATCH_MODULE_DESIGN.md` | **新增** |
| `perf_test.c`、`trace_reader.c`、`exploit/enforcing_test.sh` | **新增**诊断工具 |
| `exploit/*.log` | 真机测试日志（已 gitignore）|

---

## 4. 仓库结构

```
exploit/            GhostLock 源码（C）、目标定义、docker 构建
  src/targets/honor-REP-AN00_9.0.0.230/    ← 本机目标
ksu/                KernelSU LKM 构建脚本、补丁、用户态工具
onestep-app/        一键 App：app/src、deps/、build.sh
research/           C1 fire 定位材料：reports/、harness/、scripts/
docs/               移植说明、C1 fire 分析、平行空间/安全补丁、依赖
```

## 5. 构建

依赖（Docker/NDK、Android SDK、KernelSU、运行期）见
[`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md)。

```sh
# 1) exploit -> exploit/build/honor-REP-AN00_9.0.0.230/bin/exploit_static
PROJECT=honor-REP-AN00_9.0.0.230 ./exploit/docker-build.sh

# 2) App（从 deps/ 与 exploit 构建产物生成 assets）
cd onestep-app
./prepare-assets.sh
ANDROID_SDK=$HOME/projects/android-sdk ./build.sh     # -> build/app-signed.apk
```

预编译 APK 见 Releases。

## 6. 使用

1. 安装 APK（或把 `research/scripts/` 推到 `/data/local/tmp`，用 `adb shell`/
   Shizuku 执行）；
2. 首次开启无障碍：`设置 → 无障碍 → 一键GLKS`；
3. 点 **启动 Shizuku**，等状态变为 *完成*；
4. 点 **一键越狱**，等 *完成*；`su -c id` 应输出 `uid=0 … u:r:ksu:s0`。
5. （可选，安全功能相关）启用内置 LSPosed 模块：打开 LSPosed 管理器
   （KernelSU 模块 `zygisk_lsposed`），启用 **一键GLKS** 并勾选
   `com.hihonor.trustspace`、`com.hihonor.securityserver`、
   `com.hihonor.parallelspace`（`com.hihonor.findmydevice` 可选）。
   App 内 **安全补丁模块** 页面会显示作用域是否齐全，并可重启对应服务。

## 7. 已知限制

- 仍有极小的 fire 残余崩溃概率（见 C1 fire 文档第 7 节）；App 已降低运行期
  UI 负载以减少干扰；
- App 进程可能被系统回收，重新打开显示 *待机*；
- Shizuku 偶尔需要重启 App 才能重新收到 binder；
- 仅针对 REP-AN00 9.0.0.230，不要在其他版本上运行；
- LSPosed 模块需要在管理器中手动勾选一次作用域；LSPosed 在进程启动时注入，
  修改作用域后用 App 内“重启服务”按钮（或重启）让目标进程重新加载；
- 重启会丢失临时 root 与 `msc.parallelspace.debug_mode`，重新跑一键越狱即可
  （收尾会自动写入该属性）；
- USB 调试开着时，支付保护中心仍会报“USB 调试未禁用”，这一项与 root 无关；
- 不要用杀 `hwsecurity` HAL 的方式“修复”支付保护：会破坏查找设备，模块刻意不碰 HAL。

## 8. 致谢与许可

- 原版 PoC：[yakidango-official/GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT)；
- 荣耀 90 移植：[cyrrp/honor90-ghostlock](https://github.com/cyrrp/honor90-ghostlock)；
- KernelSU、Magisk（`magiskpolicy`）、Shizuku：归各自作者；
- 许可证：见 [`LICENSE`](LICENSE)。

> **免责声明**：本代码用于安全研究以及修复你本人拥有的设备。它会直接操作内核
> 内存、把 SELinux 置为宽容并加载未签名内核模块。风险自负；请勿用于你并不拥有
> 的设备，或用于任何违法用途。官方固件包与内核/系统镜像**不**随本仓库分发。
