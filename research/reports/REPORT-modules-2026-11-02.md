# HONOR 90 Pro（REP-AN00 / MagicOS 9.0.0.230 / Android 15）模块体检与重启归因

生成时间：2026-11-02 14:00 CST
设备：`A7VCUT3607003569`，内核 `5.10.236-android12-9-g3c84ac3cc469`，KernelSU 3.3.0（LKM），当前 `/proc/uptime` 显示内核启动于 13:41。

---

## 0. 结论速览

1. **你一共只装了 4 个模块，其中 3 个当前完全没有生效**：Zygisk 没有被注入任何进程，因此 Zygisk Assistant 和 PIF 的 Zygisk 部分都是死代码；PIF 连 prop 伪装都没写进去（实测机型属性仍是 REP-AN00）。唯一在工作的是 Tricky Store。
2. **这不是"输入模块冲突"**：`/data/adb/modules` 下没有任何输入法/触摸/按键类模块，系统里也没有 LSPosed。
3. **13:45 那次软重启有直接证据**：`zygote64` 被注入 `frida-agent-64.so` 后 abort，报 `Failed to reach single-threaded state`。frida 是我这两天排查平行空间时装进去的，不是你的模块。
4. 13:38 / 13:41 的两次启动**没有留下 tombstone**，无法归因到任何模块（那两次前后没有任何模块在注入）。

---

## 1. 模块清单与真实状态

`su -c ls /data/adb/modules/` → 4 个目录，无 `disable`、无 `remove` 标记，全部处于启用状态。

| 模块 | 版本 | 声称作用 | 实测状态 | 证据 |
|---|---|---|---|---|
| **Tricky Store** | v1.4.1 (245-72b2e84) | keystore 级密钥证明伪造（keybox） | ✅ 工作中 | 进程 `TrickyStore` pid 17766/18101 存活；`/data/adb/tricky_store/key_db/keystore.db` 已生成；`tee_status` = `teeBroken=false` |
| **Play Integrity Fork** | v18（osm0sis） | 伪装 build 属性 + Zygisk 注入 GMS 拿完整性 | ❌ 未生效 | ① 它有 `zygisk/` 目录 → `post-fs-data.sh` 走 Zygisk 分支，功能**完全依赖注入**，而注入不存在；② 旁证：`ro.product.model`=`REP-AN00`、`ro.build.version.security_patch`=`2026-08-01`，与 `custom.pif.prop` 里的 Pixel 11 Pro / `2026-10-05` 完全不同（v18 的 pif.prop 由 Zygisk 侧按进程应用，全局属性不一定是判据） |
| **Zygisk Assistant** | v2.1.4（snake-4） | 隐藏 root（Zygisk 模块） | ❌ 无作用 | 依赖 Zygisk；zygote 与任意 app 的 `/proc/*/maps` 里都没有它的 `arm64-v8a.so` |
| **ZNNext** | v2.11.0 (273-3134a2f) | 独立 Zygisk 加载器 | ⚠️ 守护进程在跑，但**不注入** | `/data/adb/zygisknextsu/znn_state.json`：`"disableZn":"true"`、`"modules":[]`、`mode:"late"`；`/proc/$(pidof zygote64)/maps` 只有 `/memfd:jit-zygote-cache`，无任何 zygisk/znn 映射 |

**结论**：当前这套组合的实际效果 = "只有 Tricky Store 在工作"。PIF + Zygisk Assistant + ZNNext 三个加起来没有产生任何运行时效果，属于纯粹的体积与不确定性来源。

> 注：`disableZn=true` 是 ZNNext 自己的配置项（`/data/adb/zygisknextsu/config`），我无法从它的压缩前端反解出该开关的中文语义，但"配置为 true + `modules:[]` + 零注入"三条证据互相一致，指向同一结论。

---

## 2. 重启归因（软重启）

### 2.1 直接证据：zygote64 被 frida 注入后自杀

`/data/tombstones/tombstone_19`（2026-11-02 13:45:03）：

```
Cmdline: zygote64
pid: 1480, tid: 1480
Abort message: 'Failed to reach single-threaded state: wait_time = 4404'
backtrace: #00 libc.so (abort+168)
maps: /memfd:frida-agent-64.so (deleted)   ← 出现 30 次（r--/r-x/r--/rw- 四段 + 各线程栈）
```

zygote 在 fork 前必须回到单线程状态；有外部 agent 在 zygote 里留了线程，就会卡住 4.4 秒然后 `abort()`。zygote 一死，init 重启 zygote → 整个 framework 重启，**内核不重启，所以 LKM/root 保留**——这与你描述的"软重启、不清 root"完全吻合。

补充：`boot_progress_start: 236625`（13:45:04）紧接着这次 abort，说明 framework 确实重启了。

### 2.2 时间线

| 时间 | 事件 | 证据 |
|---|---|---|
| 13:35–13:36 | `com.hihonor.parallelspace` 连续 abort（`selinux_android_setcontext(10184,...) failed`） | tombstone_14~17 + `system_app_native_crash@...` |
| 13:38 | system_server 启动 | `SYSTEM_BOOT@1793597932222` |
| 13:41:07 | 内核启动（btime=1793598067） | `/proc/stat` |
| 13:44:38 | parallelspace 再次 abort（同上 setcontext） | tombstone_18 |
| 13:45:03 | **zygote64 abort（frida-agent）** | tombstone_19 |
| 13:45:04 | zygote/framework 重启 | `boot_progress_start` |
| 13:53 | 我接入排查，root 仍在（`uid=0`） | `su -c id` |

**为什么基本可以排除模块**：四个模块里唯一具备"往别人的进程里注入代码"能力的只有 ZNNext，而它现在零注入（见 §1）。Tricky Store 只针对 keystore2，不碰 zygote。Zygisk Assistant 与 PIF 都是被动等待 Zygisk 调用。换句话说，**当前这套模块在技术上不具备让 zygote 崩溃的手段**；一旦你重新启用 Zygisk（要让 AdClose/LSPosed 工作就必须启用），这个结论就要重新评估。

parallelspace 那 6 次 `setcontext` 失败是上一轮 bind-mount 补丁实验的残留（user 100 已删除，当前 `pm list users` 只有 0 和 128），与模块无关，重启后不再复发。

### 2.3 关于 13:38 / 13:41

这两次启动没有对应 tombstone，`/data/system/panic*`、`/data/vendor/dontpanic` 均不存在（不是内核 panic）。当时唯一在跑的外部注入工具是 frida-server，但没有留下可归因的崩溃记录。**要定性只能做对照**：停掉 frida-server，观察 24 小时内 `SYSTEM_BOOT` 是否再增加。

---

## 3. 处置建议（只报告，未执行任何卸载）

| 模块 | 建议 | 理由 | 移除/停用后的影响 |
|---|---|---|---|
| **Zygisk Assistant** | 可移除 | Zygisk 未注入，等于没装 | 无（它现在就不起作用） |
| **ZNNext** | 可移除，或修正配置 | 只作为 Zygisk 加载器；现在既不加载模块，又不注入 | 无（前提是你不需要 Zygisk）；若将来要用 LSPosed/AdClose 才需要它 |
| **Play Integrity Fork** | 二选一：移除，或修好 | 属性伪装完全没生效，等于没装；要修需先让 Zygisk 工作 | Play Integrity 判定保持现状（现在也没通过任何伪装） |
| **Tricky Store** | 保留 | 唯一真正在工作的模块 | 移除会丢 keybox 伪造 |

如果你希望我动手，最稳的顺序是：先停 `frida-server`（一条命令，立刻降低软重启概率）→ 观察 → 再动模块（模块增删都要软重启 framework 才彻底生效，而这正好会"看起来像又一次重启"）。

---

## 4. 去广告 / 去荣耀云控 调研（2026-11-02 数据）

### 4.1 去广告：按"广泛使用"排序

| 项目 | 形式 | 星 / 叉 | 最近更新 | 与本机（MagicOS 9 / Android 15）适配 |
|---|---|---|---|---|
| [rddmwmddr/android-ad-cleaner](https://github.com/rddmwmddr/android-ad-cleaner) | 电脑端 ADB 工具，**不需要 root**，可还原 | **5729★ / 513f** | 2026-11-01 | 官方实测过荣耀 PNM-AN10（MagicOS 10）与 WKL-AN20（MagicOS 11）；**没有 REP-AN00 / MagicOS 9 专用脚本**，但有荣耀 MagicOS 手动清单，可先跑通用检测 |
| [zjyzip/AdClose](https://github.com/zjyzip/AdClose) | LSPosed 模块（应用内广告） | 1055★ / 93f | 2026-11-01 | 需要 LSPosed；本机没装，且 Zygisk 未注入 → **当前环境用不了** |
| [lingeringsound/10007](https://github.com/lingeringsound/10007) | hosts 规则 | 1032★ / 23f | 2026-10-01 | 通用；需要能改 hosts（root 或模块） |
| [217heidai/adblockfilters](https://github.com/217heidai/adblockfilters) | 规则集（配合 AdGuard/Clash/mihomo） | **7750★ / 431f** | 2026-11-02 | 通用；你已装 clash.meta，可直接当规则源 |
| [410154425/AdGuardHome_magisk](https://github.com/410154425/AdGuardHome_magisk) | Magisk/KSU 模块（DNS 层） | 626★ / 25f | 2024-05（停更） | 通用但与"私人 DNS/VPN"冲突；停更近两年，不建议新上 |
| [coderstory/HonorHook](https://github.com/coderstory/HonorHook) | LSPosed：荣耀主题商店/包管理器净化 | 9★ / 0f | 2026-10-31 | 荣耀专用但太小众，且需 LSPosed |
| [VoreulCH/HonorAdvancedSettings](https://github.com/VoreulCH/HonorAdvancedSettings) | LSPosed：安装器净化 + 液态玻璃 + 温控 | 17★ / 3f | 2026-11-01 | **仅 MagicOS 11（Android 17）**，明确"其他版本 Hook 未命中会静默失效" → 本机不适用 |

### 4.2 荣耀专有的"广告开关"清单（已验证路径）

`android-ad-cleaner` 仓库里的荣耀清单实测于 MagicOS 10，路径对 MagicOS 9 基本通用：

- 设置 → 搜索"广告" → 广告与隐私：关"智能推荐服务"，右上角显示系统程序后把广告标识读取**全部关掉**
- 设置 → 搜索"新闻资讯"：关 YOYO 建议的新闻资讯
- 下拉搜索 → 更多 → 设置：关热搜榜/热搜词、个性化管理全关
- 负一屏 → 头像 → 设置：关智能推荐、个性化广告、个性化消息、资讯精选
- 应用市场 → 我的 → 设置：内容推荐服务两项分开点；消息中心 → 活动福利/游戏动态；服务模式 → 基本服务
- 浏览器 → 设置：简洁首页；消息通知 → 新闻热点/兴趣推送；隐私管理 → 个性化内容 + 互动广告（含摇一摇）
- 主题 / 视频（优酷荣耀版）/ 音乐 / 阅读 / 系统管家：各自的"个性化/广告推荐"子项
- 魔法画报：关锁屏画报轮播
- 营销通知：荣耀的渠道 id 形如 `com.hihonor.android.pushagent.LOW.<数字>pnc`，按渠道关，保留服务类通知

### 4.3 "去荣耀云控"：没有成熟模块

我做过三类检索，结论是**否定性的**：

- GitHub 搜索 `荣耀 云控` → **0 个结果**；`云控` → 2544 个结果，前 10 全是数据库/舆情/云监控项目，无手机相关
- LSPosed 模块仓库与中文模块站：只有小米/MIUI 的"去云控"（如 `去MIUI云控`），没有荣耀版本
- 荣耀官方社区同题帖（[thread-30489418](https://club-api.c.hihonor.com/cn/thread-30489418-1-1.html)）下的回复是"开飞行模式""解决不掉""root 应该可以但不建议"——没有可复现方案

所以现实路径只有两条，且都不是"装个模块"：

1. **包级治理（不 root，可还原）**：广告/推广组件用 `pm suspend` 挂起（`android-ad-cleaner` 采用的就是 suspend 而非 `pm disable-user`，对系统更新更友好），营销通知按渠道关。
2. **网络层拦截（root）**：hosts 或 DNS（AdGuard/mihomo 规则），只能挡已知域名，对系统级个性化推荐无效——因为那些数据走的是自家 HTTPS 接口。

本机上与之相关的系统包（可作后续逐项验证对象，**未做任何改动**）：

```
com.hihonor.id                  广告标识/OAID 服务
com.hihonor.hncloud             荣耀云（云空间）
com.hihonor.android.pushagent   荣耀推送（营销通知的来源）
com.hihonor.hiviewpush          华为推送兼容层
com.hihonor.appmarket           应用市场（含推广位）
com.hihonor.assistant           YOYO 建议
com.hihonor.intelligentmessage  智能短信
com.hihonor.searchservice       搜索服务
com.hihonor.browserhomepage     浏览器主页
com.honor.browser               荣耀浏览器
com.hihonor.youku.video.overlay 视频（优酷荣耀版）
com.hihonor.hnmusicplayer       音乐
```

---

## 5. 遗留事项

- `frida-server16`（pid 17746）仍在设备上运行，`frida.log`/`f16.log` 为空。**建议先停**，这是目前唯一被证实注入过 zygote 的东西。
- SELinux 仍是 `Permissive`（为 exploit 打开的）。恢复 enforcing 需要重启内核，会丢 LKM，需要重跑一键套件。
- 一键套件里 `setprop ctl.stop hwsecurity-hal` 仍是无条件执行（你此前指出这一策略不严谨），待改成条件式或移除。
