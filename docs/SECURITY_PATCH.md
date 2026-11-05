# 支付保护中心 / 查找设备 的 root 检查分析与安全补丁设计

- 设备：HONOR 90 Pro `REP-AN00`，MagicOS 9.0.0.230（Android 15）
- 前提：GhostLock 临时 root（KernelSU LKM）
- 关联文档：`REPORT-parallel-space-2026-11-05.md`

---

## 0. 结论速查

| 组件 | 包/入口 | root 检查 | 处理方式 |
|---|---|---|---|
| 支付保护中心 | `com.hihonor.trustspace`，设置→安全→支付保护中心 | `HwSecurityDiagnoseManager.getRootStatusSync() > 0 ? 0 : 6`（0=风险，6=通过） | 在 TrustSpace 进程 hook `getRootStatusSync()` 返回 0 |
| 查找设备 | `com.hihonor.findmydevice`，`PHONEFINDER_ENTTRANCE` → `PhoneFinderActivity` | `SystemUtil.isRoot()` → `RiskDetect.isRT().getCode() != 0`，仅用于 BI/上报/JSON（非 UI 闸门） | 不需要 hook；**不要杀 HAL** |
| 平行空间 | `com.hihonor.parallelspace` | `ApplicationUtil.isRoot()`（App 层）+ `HwKeystoreServiceImpl.isRoot()`（Asset 写） | 属性/hook + securityserver hook（仅创建时） |

**关键纠正**：支付保护中心主页面显示“风险”，本次实测是 **“USB 调试未禁用”** 这一项导致的；
关闭 USB 调试后主页面即通过。root 项只影响修复页里的“手机已 Root/未 Root”一条，
关闭 USB 调试与 root hook 是两件独立的事。

---

## 1. 支付保护中心（TrustSpace）

### 1.1 入口链路

```
设置 → 安全 → 支付保护中心
└─ com.hihonor.trustspace/.settings.SettingsActivity
   └─ com.hihonor.trustspace/.mainscreen.AppListActivity
      ├─ onResume() 里跑一组 RiskScanner
      │   ├─ cloudSecurityCheck
      │   ├─ WiFi 安全检查
      │   └─ Root 状态（d1.d）
      └─ 全部通过（结果 == 6 或 3）→ “安全”
         否则 → “风险，可能造成财产损失” + “立即修复”
```

### 1.2 root 检查代码

```java
// d1/d.java
public static int d() {
    return HwSecurityDiagnoseManager.getInstance().getRootStatusSync() > 0 ? 0 : 6;
}
```

```java
// AppListActivity.onResume()
int c3 = value.c();
if (c3 != 6 && c3 != 3) {
    Log.e("AppListActivity", "scan failed,scanner: " + value.b() + " result: " + c3);
}
```

即 **root 状态 > 0 → 0（风险）**；root 状态 = 0 → 6（通过）。

### 1.3 修复页（FixRiskActivity）

实测条目：

```
风险修复
手机未 Root          ← 由上面的 root scanner 决定（hook 后从“已 Root”变“未 Root”）
锁屏密码已设置
安全短信已开启
安全输入法已开启
USB 调试未禁用       ← 主页面“风险”的真实来源（用户需要 USB 调试）
WLAN 安全可靠
```

### 1.4 验证与 hook

- Frida attach `com.hihonor.trustspace`，hook：

```js
var D = Java.use('com.hihonor.android.securitydiagnose.HwSecurityDiagnoseManager');
D.getRootStatusSync.implementation = function () { return 0; };
```

- 日志：`[ts] getRootStatusSync -> 1 ; forced 0`
- 修复页变为 **“手机未 Root”**。
- 主页面仍显示旧“风险”，是因为 `AppListActivity` 的扫描结果在首次进入时缓存、
  后续 resume 不重扫（logcat 13:43 之后无新的 `RISK_SCAN` 行）；与 root 无关。

---

## 2. 查找设备（Find Device）

### 2.1 入口链路

```
设置 → 安全 → 查找设备
└─ action com.hihonor.android.remotecontrol.PHONEFINDER_ENTTRANCE
   └─ com.hihonor.findmydevice/com.hihonor.android.remotecontrol.ui.PhoneFinderActivity
```

### 2.2 root 检查

```java
// com.hihonor.base.common.SystemUtil
public static boolean isRoot() {
    return RiskDetect.isRT().getCode() != 0;
}
```

调用点（均在 findmydevice 内）：

| 位置 | 用途 |
|---|---|
| `FindNetworkManager`（open/closeFindNetwork） | 日志里打印 isRoot |
| `FindNetworkEventUtils` | BI 事件字段 `isRoot` |
| `PhoneFinderDataModel` | JSON 里写 `KEY_ROOT_STATUS`（上报查找网络服务） |

即：**查找设备的 root 检查用于上报/日志，不是 UI 闸门**；实测 root 下 `PhoneFinderActivity` 正常打开、功能可用。

### 2.3 为什么“杀 HAL”会破坏查找设备

- 相关 HAL：`vendor.honor.hardware.hwsecurity-service`（pid 1567，system）。
- 该 HAL 不只提供 root 状态，还参与防丢失/查找网络等能力；
  直接 kill 会导致：
  - 设置页入口有时消失（依赖该服务的可用性判断）；
  - 进入后闪退（服务 binder 死亡 / 空结果）。
- **正确做法：不杀 HAL，只在需要的客户端进程里 hook root 状态判定。**

### 2.4 是否需要给查找设备做 root 伪装

- 不需要（它不 gate UI）。
- 若希望上报“未 root”，可另加 hook：`com.hihonor.base.common.SystemUtil.isRoot()` → false
  （scope `com.hihonor.findmydevice`）；默认不做，避免影响云端查找网络的行为。

---

## 3. 对平行空间方案的影响

无影响。三套 hook 相互独立、均为**进程内**：

| 方案 | 作用进程 | 机制 |
|---|---|---|
| 支付保护中心 | com.hihonor.trustspace | `getRootStatusSync()` → 0 |
| 平行空间 App 闸门 | com.hihonor.parallelspace | `msc.parallelspace.debug_mode=1` 或 hook `ApplicationUtil.isRoot()` |
| 空间密码写入 | com.hihonor.securityserver | hook `HwKeystoreServiceImpl.isRoot()` → false |

- 都不杀 HAL；查找设备保持正常。
- 都不修改系统全局状态；退出/重启进程即恢复。

---

## 4. 复现（临时验证）

```sh
# TrustSpace
frida -U -n com.hihonor.trustspace -l ts_hooks.js
# ts_hooks.js: hook HwSecurityDiagnoseManager.getRootStatusSync -> 0

# 验证
# 设置→安全→支付保护中心→立即修复：应显示“手机未 Root”
```

---

## 5. 局限

1. Frida 方案是临时的：进程重启/手机重启后失效。
2. 主页面“风险”还受 USB 调试等其它项影响，root hook 不会改变这些项。
3. `HwKeystoreServiceImpl.isRoot()` 在 securityserver 内可能还有其它调用点
   （HnUniKeystoreService 之外的主 APK 未完整反编译），全局 hook 需评估副作用。
