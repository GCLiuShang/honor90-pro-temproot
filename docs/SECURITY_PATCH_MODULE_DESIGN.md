# 安全补丁模块（LSPosed）设计分析

- 目标：把“支付保护中心 / 平行空间 / 空间密码”三类 root 绕过做成长期生效、可开关、可热重启的模块
- 载体：一键包 APK（`com.ghostlock.ksuapp`）同时作为 LSPosed 模块，由系统里已有的
  `zygisk_lsposed`（KernelSU 模块）在目标进程启动时注入
- 状态：**仅设计分析，未开始编码**

---

## 1. 为什么必须上 LSPosed

| 方案 | 持久性 | 部署成本 | 进程覆盖 | 结论 |
|---|---|---|---|---|
| Frida attach | 否（进程/重启即失效） | 需 frida-server + 每次手动 attach | 手动逐个进程 | 仅实验用 |
| 改系统属性 | 是（重启丢失） | setprop 一条命令 | 全局 | 只覆盖 App 层，不覆盖 Asset/TrustSpace |
| 杀 HAL | 是 | 粗暴 | 全局副作用 | ❌ 破坏查找设备 |
| **LSPosed 模块** | 是（随进程启动注入） | 一次安装 + LSPosed 作用域勾选 | 按包名精确覆盖 | ✅ 推荐 |

支付保护中心的 root 检查在 `AppLifecycle` 之外没有可行的属性开关，
且 `securityserver` 的 Asset 写检查只在创建空间时需要，但**无法预知用户何时创建**，
所以必须让 hook 在目标进程启动时就位 → LSPosed 是正解。

---

## 2. 与一键包 APK 的整合方式

LSPosed 模块本质是普通 APK，只需在 APK 里加模块元数据与入口清单，**不需要单独打包**：

```xml
<!-- AndroidManifest.xml -->
<meta-data android:name="xposedmodule" android:value="true"/>
<meta-data android:name="xposeddescription"
           android:value="GhostLock 安全补丁：支付保护中心/平行空间的 root 检测绕过"/>
<meta-data android:name="xposedminversion" android:value="93"/>
<meta-data android:name="xposedscope" android:resource="@array/xposed_scope"/>
```

```
assets/xposed_init          →  com.ghostlock.ksuapp.patch.PatchEntry
res/values/arrays.xml       →  xposed_scope = trustspace / securityserver / parallelspace
```

入口类：

```java
public class PatchEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(LoadPackageParam lpparam) {
        Config cfg = Config.load();          // 读取开关，默认全开
        if (!cfg.master) return;
        switch (lpparam.packageName) {
            case "com.hihonor.trustspace":    TrustSpaceHooks.install(lpparam, cfg); break;
            case "com.hihonor.securityserver": SecurityServerHooks.install(lpparam, cfg); break;
            case "com.hihonor.parallelspace":  ParallelSpaceHooks.install(lpparam, cfg); break;
        }
    }
}
```

一键包原有功能（Shizuku/root、日志、任务清单）不受影响；LSPosed 只在被勾选的作用域进程里加载入口类。

---

## 3. Hook 清单

| # | 作用域包 | 目标类/方法 | 行为 | 默认 | 备注 |
|---|---|---|---|---|---|
| 1 | `com.hihonor.trustspace` | `com.hihonor.android.securitydiagnose.HwSecurityDiagnoseManager#getRootStatusSync()` | 返回 0 | 开 | 支付保护中心“手机未 Root” |
| 2 | `com.hihonor.securityserver` | `com.hihonor.securityserver.HwKeystoreServiceImpl#isRoot()` | 返回 false | 开 | Asset 写（创建/重设空间密码） |
| 3 | `com.hihonor.parallelspace` | `com.hihonor.parallelspace.common.util.ApplicationUtil#isRoot()` | 返回 false | 开 | 替代 `msc.parallelspace.debug_mode=1` |
| 4（可选） | `com.hihonor.findmydevice` | `com.hihonor.base.common.SystemUtil#isRoot()` | 返回 false | 关 | 只影响上报，非必需 |
| 5（不做） | parallelspace | `HuksTaClient#getSecret()` → 常量 | — | — | 会破坏原生开空间，**不纳入** |

说明：

- #2 的调用点：`HnUniKeystoreService.apk` 内 `HwKeystoreServiceImpl.isRoot()` 被
  `libhwkeystore_jni.so`（ASSET_PWD）调用；主 APK 是否还有其它调用点未完整反编译，
  上线前需再确认（见 §7 风险）。
- #4 默认关闭：查找设备不 gate UI，改上报值可能与云端“查找网络”行为冲突。
- #5 明确不做：常量 secret 只在实验里验证过，正常使用会要求每次挂 hook，不适合产品化。

---

## 4. 开关与配置

- 存储：模块 APK 自己的 `SharedPreferences`（`patch_config`）。
- 读取：hook 进程与 App 进程不同 uid，使用 `XSharedPreferences`（LSPosed 提供）或
  模块私有目录下 `chmod 644` 的 JSON 文件（App 自己写、hook 侧只读）。
- 字段：

```json
{
  "master": true,
  "trustspace_root": true,
  "securityserver_assetroot": true,
  "parallelspace_isroot": true,
  "findmy_report_root": false
}
```

- 默认（文件不存在）：全开（#4 除外）。
- 修改后：对应服务需重启才生效（LSPosed 在进程启动时注入）→ 见 §5。

---

## 5. 服务重启按钮（替代软重启）

模块 UI 提供三个重启按钮，调用 Shizuku/root 执行：

```sh
am force-stop com.hihonor.trustspace      # 下次进入支付保护中心时重新注入
am force-stop com.hihonor.securityserver   # 系统常驻，通常会被系统/客户端重新拉起
am force-stop com.hihonor.parallelspace    # 下次进入平行空间时重新注入
```

注意：

- `securityserver` 是 system uid 常驻服务，force-stop 后可能被 system_server 立刻重启；
  若未重启，可用 `am startservice`/触发一次认证操作拉起。
- 重启 `parallelspace` 会关闭当前平行空间（用户需重新输入空间密码）。
- 建议在 UI 上对每个按钮标注影响（例如“会退出当前平行空间”）。

---

## 6. `msc.parallelspace.debug_mode=1` 能否放进 LSPosed 模块？

### 6.1 三种做法

| 做法 | 可行性 | 说明 |
|---|---|---|
| A. 模块内 `SystemProperties.set("msc.parallelspace.debug_mode","1")` | ❌ | 模块代码运行在目标 App 的 uid（untrusted_app/system），
没有写 `msc.parallelspace.*` 属性上下文的权限；SELinux 会拒绝 |
| B. hook `ApplicationUtil.isRoot()` 返回 false | ✅ | 与属性等价，且只影响 parallelspace 进程 |
| C. 保持由一键包在拿到 root 后 `setprop` | ✅ | 属性是全局的，覆盖所有用户/进程；重启后需重设 |

### 6.2 决定：B + C 双保险

- **模块内做 B**（scope `com.hihonor.parallelspace`）：主用户进程的创建/进入 UI 直接通过，
  不依赖属性；
- **一键包继续做 C**：拿到临时 root 后自动 `setprop msc.parallelspace.debug_mode 1`。
  原因：
  1. 平行空间的 `InitActivity` 运行在 **user 100**；LSPosed 对** secondary user 进程**
     的注入覆盖不确定（取决于 LSPosed 版本/多用户设置），而系统属性对所有用户可见；
  2. 用户关闭模块/未勾选作用域时，属性仍是兜底；
  3. 属性只被 `ApplicationUtil.isRoot()` 读取，无其它副作用。
- 不在模块里写属性（A 不可行），也不把属性当作唯一手段。

---

## 7. 风险与待确认

1. **securityserver 注入**：system uid 常驻进程，LSPosed 可注入，但需确认
   在开机早期/被系统重启后模块仍生效；用重启按钮 + 状态检测兜底。
2. **`HwKeystoreServiceImpl.isRoot()` 的其它调用点**：若主 APK 里还有别处用它做
   安全判定，全局 hook 可能放宽其它保护；上线前反编译主 APK 确认。
3. **多用户**：user 100 的 parallelspace 进程是否被 LSPosed 注入需实测；
   不生效时依赖属性（§6.2 C）。
4. **与 TrickyStore/Zygisk 共存**：两者都改 keystore/zygote 行为，
   需回归测试 Asset 写、支付保护、查找设备。
5. **LSPosed 作用域需手动勾选**：安装后用户需在 LSPosed 管理器里启用模块并勾选
   三个包；一键包 UI 可检测并提示。
6. **模块被检测**：Honor 系统应用可能做 hook 检测（尤其 securityserver）；
   目前 Frida 注入未被检测，但 LSPosed 的注入方式不同，需观察。

---

## 8. 验证计划（编码后）

| 用例 | 步骤 | 预期 |
|---|---|---|
| 支付保护 root 项 | 打开支付保护中心 → 立即修复 | “手机未 Root” |
| 支付保护主页面 | 关闭 USB 调试后进入 | “安全”（无“风险”横幅） |
| 平行空间进入 | root + 模块开启，不设属性 | 正常进入（user 0；再测 user 100） |
| 平行空间创建 | 删除后重建 | 成功（securityserver hook 生效） |
| 查找设备 | 打开 PhoneFinderActivity | 正常，无闪退/入口消失 |
| 开关关闭 | 关掉 trustspace hook → 重启 TrustSpace | 恢复“手机已 Root” |
| 服务重启按钮 | 点重启 TrustSpace → 再进 | hook 重新生效 |

---

## 9. UI 设计（文字稿）

一键包主界面（现有任务清单下方、日志按钮下方）新增按钮：

```
[ 查看日志 ]
[ 安全补丁模块 ]      ← 新增
```

点击进入 `PatchSettingsActivity`：

```
安全补丁模块
────────────────────────
总开关                [开]
支付保护中心 root 检测  [开]   [重启 TrustSpace]
平行空间 App root 闸门  [开]   [重启 parallelspace]
空间密码写入 root 检查  [开]   [重启 securityserver]
查找设备 root 上报      [关]   [重启 findmydevice]
────────────────────────
状态：LSPosed 已启用 / 作用域已勾选 / 各服务运行中
提示：修改开关后需重启对应服务；重启平行空间会退出当前空间
```

- 开关默认全开（查找设备上报默认关）。
- 每个开关旁有对应服务的重启按钮。
- 顶部显示 LSPosed 是否安装、模块是否启用、作用域是否包含三个包。

---

## 10. 交付物（后续编码时）

1. `PatchEntry` + 三个 Hook 类 + `Config`
2. `PatchSettingsActivity` + 布局 + 开关持久化
3. Manifest 模块元数据、`assets/xposed_init`、`res/values/arrays.xml`
4. 一键包 `run_ksu.sh` 增加 `setprop msc.parallelspace.debug_mode 1`（§6.2 C）
5. 回归脚本：上述 8 条用例
