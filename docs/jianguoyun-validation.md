# 坚果云自动加密备份：验证记录

验证日期：2026-10-08。

本文件记录本轮实际执行过的检查、结果和未验证事项。未执行的检查不会标记为通过。

## 1. 环境

| 项目 | 值 |
| --- | --- |
| JDK | OpenJDK 21.0.10（Android Studio JBR） |
| Android SDK | Android SDK（`ANDROID_HOME`），compileSdk/targetSdk 36 |
| 单元测试 | JUnit 4 + Robolectric 4.16.1 + MockWebServer 4.12.0 |
| 设备 | AVD `Pixel8_API36`（Android 16）。另有一台物理设备，未用于自动化测试，以避免清除真实数据 |
| 新增依赖 | OkHttp 4.12.0、WorkManager 2.10.1（测试：mockwebserver、work-testing） |

## 2. 构建与静态检查

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 构建帮助任务（P0 基线） | `.\gradlew.bat help --console=plain` | 通过 |
| 基线构建 + 单元测试 | `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain` | 通过，5m29s，57 个任务全部执行 |
| 全量校验 | `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` | 通过，3m01s，67 个任务（Lint 无错误） |
| 最终全量校验（含真实联调修复后） | `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` | 通过，1m30s，67 个任务；22 个测试类、129 个用例、0 失败、0 错误 |

构建产物：`app/build/outputs/apk/debug/app-debug.apk`，26,469,005 字节（约 25.2 MiB），生成时间 2026-10-08 15:26，SHA-256 `FD5B029F04F4E07C828E3DF617BF19173A5216F41CFD444E71C2A9445CC6661B`。

## 3. 新增单元测试

| 测试类 | 用例数 | 覆盖内容 | 结果 |
| --- | --- | --- | --- |
| `NutstoreCredentialStoreTest` | 11 | 默认配置、保存/读取、路径逐段校验、保留数量边界、账号与密码校验、损坏文件、密文篡改、密钥缺失、凭据缺失、退出授权、全部清除 | 全部通过 |
| `WebDavClientTest` | 19 | MKCOL 幂等、Depth 1 列表、自条目过滤、XML 命名空间与异常 XML、越界 href、目录范围校验、路径百分号编码、401/429/5xx 分类、Retry-After、重定向不跟随、读超时、下载超限（分块响应）、取消（预取消与进行中）、探测文件读写与清理失败 | 全部通过 |
| `NutstoreBackupStoreTest` | 14 | 标准加密导出与密码解密恢复、明文与无密码槽拒绝、快照缺失、摘要损坏、请求版本合并、配置变更失效、确认上传、失败与清理状态记录、本地快照裁剪、状态文件损坏 | 全部通过 |
| `NutstoreUploaderTest` | 10 | 成功上传与远程版本裁剪、同名幂等重试、内容校验失败保留快照、清理失败不影响成功状态、不完整列表禁用清理、配置切换中止上传、断网可重试、限流窗口阻止重复请求、无待上传任务快速跳过、明文仓库拒绝 | 全部通过 |
| `NutstoreBackupManagerTest` | 5 | 关闭自动备份时不调度、开启时入队唯一工作、启动恢复重新入队、关闭开关清理待上传快照但保留凭据、紧急清除删除状态与凭据 | 全部通过 |
| 合计 | 59 |  | 全部通过 |

另外，`VaultRepository` 的统一文件锁与 `readExportableVaultFile` 改动包含在 `NutstoreBackupStoreTest` 与既有 `VaultTest`/`SlotTest` 中回归。

## 4. 设备测试

命令：

```powershell
adb devices
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

执行结果：AVD `Pixel8_API36`（Android 16）上共 23 个用例，18 个通过，5 个失败。

失败用例：

1. `BackupExportTest#testSeparateExportPassword`
2. `BackupExportTest#testChangeBackupPassword`
3. `BackupExportTest#testChangePasswordHavingBackupPassword`
4. `BackupExportTest#testPlainVaultExportEncryptedJson`
5. `IntroTest#testIntro_Import_Encrypted`

判断与证据：

- 本轮 23 个用例在同一个 instrumentation 进程中运行（日志为 "Starting 23 tests"），项目配置的 `clearPackageData` 只有在 Android Test Orchestrator 生效时才清理应用数据，因此用例之间存在状态污染；`BackupExportTest` 中三个失败用例都失败在"设置备份密码后备份密码槽数量应为 1"的断言上，属于 UI 操作未在预期时点生效/状态未隔离的表现。
- 为区分回归与环境问题，临时停用了本轮全部三个运行时接入点（应用启动恢复、`VaultManager.saveAndBackup()` 调度、备份设置入口）后，以同样的命令单独重跑 `testSeparateExportPassword` 与 `testIntro_Import_Encrypted`，两个用例仍然以完全相同的错误失败：
  - `BackupExportTest#testSeparateExportPassword`：`AssertionError: expected:<0> but was:<1>`
  - `IntroTest#testIntro_Import_Encrypted`：`IllegalStateException: Vault manager is not initialized`
- 因此这 5 个失败与本轮的坚果云改动无关，属于既有 UI 测试在当前 API 36 模拟器环境下的失败。对照实验后已恢复被停用的接入点，并重新执行构建与单元测试（通过）。

结论：设备测试未全部通过，失败项按上述记录保留为环境既有问题，不能视为本轮功能验收的通过证据。

补充：本轮新增的两个设备端测试 `NutstoreLiveIntegrationTest` 与 `NutstoreBackupsActivityTest` 在同一模拟器上执行并通过（2 个用例，0 跳过、0 失败），详见第 5 节。

## 5. 真实坚果云联调（已执行）

使用真实账号（账号与应用密码仅通过环境变量或 instrumentation 参数传入，未写入任何项目文件；联调结束后已检查项目目录，凭据文本无残留）完成以下验证。

### 5.1 协议层（JVM 直接调用生产代码 `WebDavClient`）

在临时目录中编译一个调用项目生产类的检查程序，对 `https://dav.jianguoyun.com/dav/` 执行：

1. 连接探测：创建根目录 → PUT 探测文件 → GET 回读 → DELETE 探测文件，结果 OK。
2. 完整生命周期：MKCOL 设备目录 → PUT 快照文件 → GET 回读内容一致 → PROPFIND 列表可见（列表完整）→ DELETE 文件 → 再次列表为空 → DELETE 设备目录成功。
3. 清理后 `Aegis` 根目录为空。

结论：认证、目录创建、上传、列表解析、下载、内容一致性和删除均在真实服务器上通过。

### 5.2 端到端（模拟器内运行 `NutstoreUploader`，`NutstoreLiveIntegrationTest`）

测试流程：初始化一个加密数据库 → 使用生产 `NutstoreCredentialStore`（真实 Android Keystore）保存真实账号凭据 → 记录备份请求 → 运行生产 `NutstoreUploader` 上传 → 验证状态为 SUCCESS 且已确认、本地快照已删除 → 使用 `WebDavClient` 下载云端文件 → 用数据库密码解密并确认内容 → 删除云端测试产物。

结果：通过（`skipped=0`，用例耗时 2.6s）。说明 Keystore 凭据存储、加密快照生成、上传、下载校验、状态确认与远程清理在真实设备 + 真实服务器上闭环成立。

### 5.3 界面冒烟（`NutstoreBackupsActivityTest`）

结果：通过（4.4s）。设置界面在设备上正常渲染；填写账号、应用密码和远程目录后点击保存，凭据经真实 Keystore 加密后可从 `NutstoreCredentialStore` 读回并匹配。

### 5.4 联调发现并修复的缺陷

- `DocumentBuilderFactory.setXIncludeAware(false)` 在 Android 的 XML 实现上抛出 `UnsupportedOperationException`（不支持的规范版本），而 JVM 单元测试环境不会触发。影响：在真实设备上第一次 PROPFIND 解析前即崩溃，云端备份不可用。修复方式：将该调用（连同 `setExpandEntityReferences` 与各 `setFeature`）改为容错调用，Android 上忽略不支持项，`EntityResolver` 仍然阻止外部实体。

### 5.5 坚果云服务端行为记录

- 删除 WebDAV 根级目录（如 `/dav/Aegis-IntegrationTest`）返回 HTTP 403，即使目录为空；删除更深一层目录（如 `/dav/Aegis-IntegrationTest/<设备目录>`）返回 204。应用只会删除 `根目录/设备目录` 内的文件，不删除目录，因此不受影响。
- 联调在账号根目录留下一个空的 `Aegis-IntegrationTest` 文件夹（WebDAV 无法删除根级目录）。可在坚果云网页端手动删除，或保留。

## 6. 尚未执行的验证

以下场景未在真实设备上完整演练，交付后建议由使用者在设备上核对一遍：

1. 完整界面流程：在"备份 → 坚果云备份"中开启自动备份后新增/编辑/删除条目，观察状态从等待上传到上传成功。
2. 断网、切换网络、系统结束进程后的自动重试与启动恢复（Android"强行停止"后系统不会自动执行任务，属于平台限制）。
3. 首次安装从欢迎页"从坚果云恢复"的完整界面流程（下载 → 输入备份密码 → 完成初始化）。
4. 紧急清除、退出授权、关闭数据库加密在真实上传过程中的表现。
5. 独立备份密码、修改密码后的新旧版本、多设备目录保护与版本裁剪的界面表现。

## 7. 已通过自动化验证的功能点

- 凭据以独立 Keystore 别名加密保存在 `noBackupFilesDir`，不进入 SharedPreferences、系统备份或任务参数；密码字段不参与自动填充与界面状态保存。
- 根目录只接受相对路径，拒绝`..`、URL、查询片段与控制字符；保留数量限制为 1–100。
- WebDAV 客户端固定使用坚果云 HTTPS 地址，不跟随重定向，校验所有返回 href 的主机与目录范围，请求、XML、上传和下载均有大小上限。
- 快照复用标准 Aegis 加密导出（含独立备份密码规则），指纹槽被去除；明文仓库与没有可恢复密码槽的仓库会被拒绝。
- 上传使用持久文件名，重试幂等；先下载校验摘要一致，再记录成功；清理旧版本仅在完整目录列表且新备份确认成功之后执行，且不会删除刚上传的文件。
- 失败分类、重试上限、Retry-After 等待窗口、并发状态合并与配置版本失效均已通过单元测试。
- 关闭自动备份清理待上传快照但保留凭据；退出授权、关闭数据库加密与紧急清除会删除凭据、快照与任务状态。

## 8. 已知限制

- 坚果云不允许通过 WebDAV 删除根级目录（返回 403）；应用只删除 `根目录/设备目录` 内的文件，因此不受影响。
- `MAX_UPLOAD_BYTES` 与 `MAX_DOWNLOAD_BYTES` 均为 25 MiB；超过该大小的数据库无法上传或恢复。
- 设备测试中的 5 个既有失败尚未修复（需要启用 Orchestrator 或修复 API 36 下的 UI 测试时序）。
- 紧急清除无法撤回已经发送到服务器的请求，云端历史备份不会被自动删除。
- 完整界面流程（首次安装恢复、断网重试、紧急清除竞态）仍需按第 6 节人工核对。
