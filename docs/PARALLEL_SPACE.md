# 平行空间（Parallel Space）与临时 root 兼容性分析

- 设备：HONOR 90 Pro `REP-AN00`，MagicOS 9.0.0.230（Android 15），
  `9DLDLD230SP1CHNC00E215`，kernel `5.10.236-android12-9-g3c84ac3cc469`
- root：GhostLock CVE-2026-43499 + KernelSU LKM（临时 root，重启即失效）
- 日期：2026-11-05

---

## 0. 结论速查

| 设备状态 | 平行空间不存在 | 平行空间已存在 |
|---|---|---|
| 未 root | 什么都不需要（原生创建） | 什么都不需要（原生进入/销毁） |
| 临时 root | `msc.parallelspace.debug_mode=1` + securityserver 的 `isRoot()` hook（**只在创建时**，成功后即可撤） | 只需 `msc.parallelspace.debug_mode=1`（进入/设置/销毁都不需要 hook） |

- 唯一被 root 拦截的写操作是 **创建时的“设置空间密码”**（AssetManager `assetInsert`，返回 -319）。
- AssetManager 的**读**（`assetSelect`，进入/销毁时的密码校验）不查 root，返回 0。
- TEE（`createPsMk` / `getSecret` / `unlockUserKey`）在临时 root 下工作正常，没有 -5。

---

## 1. 分析链路（反编译 + 动态验证）

反编译对象：
- `/product_h/region_comm/china/app/HnParallelSpace/HnParallelSpace.apk`（13.0.10.117）
- `/system/app/HnCoAuthService/HnCoAuthService.apk`（13.0.0.6）
- `/system_ext/app/HnSecurityServer/HnUniKeystoreService.apk`
- `/system_ext/lib64/libhwkeystore_jni.so`

### 1.1 创建链路

```
ParallelLauncherGuideActivity.createParallelSpace()
└─ SpaceDispatcher.createPSpace(UserMgrResultCb)
   └─ MidUserManager.createPSpace()            // workThread, HUKS_LOCK 串行
      ├─① HuksTaClient.createPsMk()            // TEE: MK is_exist / create
      ├─② doCreatePSpaceUser()
      │     └─ UserManagerEx.createProfileForUser(... PARALLEL_SPACE_FLAG=0x20000000)
      ├─③ initFileEncryptionEnv()
      │     ├─ HuksTaClient.getSecret()        // TEE: MK init + getSecret (32B)
      │     └─ StmProxy.addUserKeyAuth()
      │           └─ StorageManagerEx.setUserKeyProtection(userId, secret)
      └─④ fixStrategyOnPsCreated()
```

结论：**这条链路本身不查 root**。root 下用 RPC 直接调用：
`createPsMk()→0`、`getSecret()→32B`、`addUserKeyAuth()→true`，user 创建成功。

### 1.2 运行（进入）链路

```
ParallelSpaceSettingsGuideActivity（或桌面入口）
└─ MidUserManager.openPSpace()
   ├─ ProfileUtil.isParallelSpaceExist()
   ├─ decryptFileSys()
   │   ├─ HuksTaClient.getSecret()             // TEE
   │   └─ StmProxy.unlockUserKey()
   │       └─ StorageManagerEx.unlockUserKey(userId, serial, secret)
   └─ doStartParallelSpace() → AmProxy.startProfile()
└─ user 100 内 InitActivity → ParallelSpaceLauncherActivity（空间桌面）
```

结论：**这条链路也不查 root**；TEE 调用在 root 下正常，`unlockUserKey` 只要求调用者是 App 自身 uid。

### 1.3 空间密码（认证）链路 —— 真正的 root 卡点

```
ParallelSpaceSettingsGuideActivity.toSetSpacePwd()      // 创建流程，requestCode=202
└─ coauth: EnhancedLockAuthSettingActivity → SetPasswordActivity → PasswordProtectInitActivity
   └─ PasswordProtectInitFragment.endButtonClick()
      └─ checkCustomValueEnable()
         ├─ CustomPwdUtils.setPassword(ciphertext, callerKeyName, COLUMN_WD_SALT)
         │    └─ AssetUtils.replacePassword()
         │        └─ HwAssetManager.assetInsert()          // ★ 写
         │            └─ com.hihonor.securityserver (ASSET_PWD)
         │                └─ HwKeystoreServiceImpl.isRoot()
         │                    └─ HwSecurityDiagnoseManager.getRootStatusSync() != 0
         │                        → 拒绝，resultCode = -319
         └─ 失败 → finishActivity(10002)
              → App: onActivityResult(202, 10002) → “set space password failed” → 回到开头
```

失败日志（12:53）：

```
I/ASSET_COMMON(3921): the device is rooted
E/ASSET_COMMON(3921): IsRoot: debug file is not exist
E/ASSET_PWD(3921): AssetHandleReq: the device has been rooted!
E/HwAssetManager(4011): assetHandleOutInfo opCode: 2, assetHandleReq failed!
W/AppLockBG(4011): AssetUtils:isResultValid: result invalid, code = -319
E/HnParallelSpace: ParallelSpaceSettingsGuideActivity:set space password failed
```

`HwKeystoreServiceImpl.isRoot()` 源码（反编译）：

```java
public static boolean isRoot() {
    HwSecurityDiagnoseManager mgr = HwSecurityDiagnoseManager.getInstance();
    int rootStatusSync = mgr.getRootStatusSync();
    LogUtil.error(TAG, "root:" + rootStatusSync);
    return rootStatusSync != 0;
}
```

### 1.4 root 检测点清单

| 位置 | 用途 | 绕过方式 |
|---|---|---|
| `ApplicationUtil.isRoot()`（HnParallelSpace） | 创建/进入/InitActivity 的 UI 闸门 | `msc.parallelspace.debug_mode=1`（代码里显式判断） |
| `HwKeystoreServiceImpl.isRoot()`（securityserver） | AssetManager **写**（assetInsert） | Frida hook 返回 false（或 `getRootStatusSync()→0`） |
| AssetManager **读**（assetSelect） | 进入/销毁时校验密码 | 不查 root，无需处理 |

`ApplicationUtil.isRoot()` 源码：

```java
public static boolean isRoot() {
    HwSecurityDiagnoseManager mgr = HwSecurityDiagnoseManager.getInstance();
    if (mgr == null) return true;
    return (mgr.getRootStatusSync() == 0
         || SystemPropertiesEx.getInt("msc.parallelspace.debug_mode", 0) == 1
         || isDemoVersion()) ? false : true;
}
```

---

## 2. 实验与证据

| 实验 | 条件 | 结果 |
|---|---|---|
| E1 后端创建（真实 TEE secret） | root，Frida RPC `createPSpace` | `createPsMk→0`、`getSecret→32B`、`addUserKeyAuth→true`、user 创建成功 |
| E2 常量 secret | hook `getSecret→K`、`createPsMk→0` | `setUserKeyProtection(K)` 成功；open 时 `unlockUserKey(K)→true` |
| E3 UI 创建（无 hook） | root + property=1 | 密保“完成”后失败：`resultCode=10002`、`assetInsert -319` |
| E4 UI 创建（hook securityserver） | root + property=1 + `isRoot()→false` | 全流程成功：user 100 创建、`RUNNING_UNLOCKED`、空间桌面打开 |
| E5 进入（零 hook） | root + property=1 | `assetSelect→0`；`InitActivity` → `ParallelSpaceLauncherActivity`；`RUNNING_UNLOCKED` |
| E6 销毁（零 hook） | root + property=1 | 密码校验 `assetSelect→0`；user 100 被删除；`parallel_space_status=0`、`auth_type=0` |
| E7 root 侧解锁 CE（在 user 101 + 常量 K 上验证） | root 工具 + `setuid(10184)` + secret | `unlockUserKey` 成功；`/data/media/101` 由乱码变明文；user 100 + TEE secret 的同法尚未实测 |

关键数字（E4 创建出的空间）：
- user id 100，serial 139
- TEE secret：`b81622869481826adffa5bc714ca3ef28a58ca7f5e62e0aba8c6d3b0021a27b4`
- `parallel_space_status=1`，`auth_type=9`（keyguard + 自定义密码）

E7 的 root 侧解锁工具（本目录 `tool/PsUnlock.java`）要点：
- `app_process` 以 root 启动；
- 先 `Os.setuid(10184)`（App uid），否则 securityserver 返回 “Caller is not allowed”；
- 反射调用 `com.hihonor.android.os.storage.StorageManagerEx.unlockUserKey(userId, serial, secret)`。

---

## 3. 为什么这个策略有效

1. **App 层 root 闸门**只读一个系统属性；`debug_mode=1` 直接短路，不需要改 App。
2. **TEE 调用**（createPsMk/getSecret/unlockUserKey）不受临时 root 影响：
   TA 的“设备状态异常”看的是 TEE 侧状态（bootloader/vbmeta），LKM 不改这些；
   实测 root 下 `createPsMk=0`、`getSecret` 正常返回。
3. **AssetManager 写**才查 root，且只查 `getRootStatusSync()`；
   hook securityserver 的 `isRoot()` 返回 false 即可，且**只需在创建那一次**。
4. **AssetManager 读**不查 root，所以进入/销毁永远不需要 hook。
5. **密码校验**走 assetSelect，与 root 状态无关。

---

## 4. 复现步骤

### 4.1 临时 root 后

```sh
su -c 'setprop msc.parallelspace.debug_mode 1'
```

### 4.2 仅在“创建/重设空间密码”时挂 hook

设备上运行 frida-server（attach 模式，不要 spawn，避免 zygote 注入）：

```sh
su -c 'setsid /data/local/tmp/frida-server16 -l 0.0.0.0:27042 >/dev/null 2>&1 < /dev/null &'
```

在 `com.hihonor.securityserver` 进程注入：

```js
Java.perform(function () {
  var K = Java.use('com.hihonor.securityserver.HwKeystoreServiceImpl');
  var orig = K.isRoot;
  K.isRoot.implementation = function () {
    var r = orig.call(this);
    console.log('[sec] isRoot() -> ' + r + ' ; forced false');
    return false;
  };
  var D = Java.use('com.hihonor.android.securitydiagnose.HwSecurityDiagnoseManager');
  var g = D.getRootStatusSync;
  D.getRootStatusSync.implementation = function () {
    var r = g.call(this);
    console.log('[sec] getRootStatusSync() -> ' + r + ' ; forced 0');
    return 0;
  };
});
```

### 4.3 UI 流程

- 创建：设置 → 安全 → 平行空间 → 开启 → 锁屏密码 → 空间密码 → 密保 → 完成
- 进入：设置 → 安全 → 平行空间 → 输入空间密码
- 销毁：空间设置 → 销毁平行空间 → 输入空间密码

### 4.4 验证命令

```sh
pm list users                                   # user 100 是否存在
dumpsys user | grep -A3 'UserInfo{100'          # RUNNING_UNLOCKED / serial
getprop msc.parallelspace.debug_mode            # 1
logcat -d | grep -E 'ASSET_PWD|HnParallelSpace' # assetSelect/assetInsert 返回码
```

---

## 5. 局限与风险

1. 临时 root 每次重启失效；`msc.parallelspace.debug_mode` 也随重启丢失。
   **重启后若未取 root，不需要任何设置；若重新取 root，需要重新 setprop。**
2. `msc.parallelspace.debug_mode=1` 是 App 自带的调试开关，属于“官方后门”式绕过；
   它只影响 `ApplicationUtil.isRoot()`，不改变系统其它安全组件。
3. 创建/重设空间密码时若在 root 下，必须临时挂 securityserver hook；
   挂 hook 需要 frida-server（或等价的 LSPosed 模块/bind-mount 补丁）。
4. 销毁空间会 `destroyMk()` 销毁 TEE 主密钥；之后重建会生成新的 secret。
5. 未实测“非 root 时创建、再取 root”的组合；按代码与已测数据推断同样可用
   （TEE 不受临时 root 影响、assetSelect 不查 root）。
6. 2026-11-05 12:47 出现过一次 zygote64 软重启
   （`Failed to reach single-threaded state`，同时 keychain setcontext 失败），
   与平行空间流程无直接因果关系；设备装有 tricky_store / zygisk_lsposed / zygisksu，
   更可能与 Zygisk/LSPosed 的 zygote 注入相关，待后续单独分析。

---

## 6. 附录：关键日志

E3（无 hook，UI 创建失败，12:53）：

```
12:53:41.018 W/AppLockBG(4011): AssetUtils:isResultValid: result invalid, code = -319
12:53:41.001 I/HnParallelSpace: ParallelSpaceSettingsGuideActivity:resultCode=10002
12:53:41.001 E/HnParallelSpace: ParallelSpaceSettingsGuideActivity:set space password failed
```

E4（hook securityserver，UI 创建成功，12:57）：

```
[sec] isRoot() -> true ; forced false
[sec] isRoot hooked, now=false
[sec] getRootStatusSync hooked
（此后 UI 流程走完，user 100 出现并 RUNNING_UNLOCKED）
```

E5（进入，零 hook）：

```
13:16:50.693 I/ASSET_PWD(3921): AssetHandleReq: apiName : assetSelect
13:16:50.744 I/ASSET_PWD(3921): AssetHandleReq: return code: 0
13:13:14.969 I/HnParallelSpace: InitActivity-1:onCreate
13:13:14.972 I/HnParallelSpace: InitActivity-1:startLauncherActivity
```

E6（销毁，零 hook，13:20）：

```
13:20:00.754 I/ASSET_PWD(3921): AssetHandleReq: return code: 0
13:20:09.965 I/HnParallelSpace: ParallelSpaceSettingsActivity:onStop actionExit
（pm list users 中 user 100 消失；parallel_space_status=0、auth_type=0）
```
