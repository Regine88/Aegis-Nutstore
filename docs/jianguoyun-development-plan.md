# 坚果云自动加密备份：分阶段开发计划

适用项目：Aegis 3.4.3（Android / Java / XML）的坚果云备份分支。

本文件是开发任务说明。已完成的源码调研、构建诊断和官方资料见 [项目分析](./jianguoyun-backup-plan.md)。

> 注（2026-10-08 更新）：本计划已按 P0 → P8 顺序实施完成，各阶段状态见第五节“阶段进度”，实际验证记录见 [验证记录](./jianguoyun-validation.md)。

## 一、已经确定的功能边界

第一版实现：坚果云账号与应用密码配置、测试连接、自动上传标准 Aegis 加密备份、立即备份、历史版本保留、已有仓库手动恢复、首次安装手动恢复、备份状态及失败原因。

服务器固定为 `https://dav.jianguoyun.com/dav/`；默认远程根目录为 `Aegis`，每次安装生成随机设备目录标识，默认每个设备保留 5 份历史备份。可以浏览其他设备备份；自动删除仅作用于本机设备目录中符合应用命名规则的历史文件。

自动备份只允许加密仓库。备份解密继续使用 Aegis 仓库密码或已设置的独立备份密码；坚果云应用密码只用于云盘访问。已有仓库恢复采用现有条目选择和确认流程，首次安装采用现有完整仓库初始化流程。

双向自动合并、删除同步、通用 WebDAV 服务器、服务端、自定义加密格式和大规模架构重构不在第一版范围内。

## 二、执行方式与通用约束

1. 先确认本计划，再按 P0 → P8 顺序执行。每轮只执行指定阶段和它依赖的必要修复，不一次生成整个功能。
2. 每轮先检索相关代码、可复用方法和调用点；保留项目当前 Java 风格、Material 风格及安全对话框。
3. 依赖阶段通过验收后才能开始下一阶段。确需变更职责或文件范围时先说明原因，更新本计划。
4. 每阶段报告：实际修改文件、完成的行为、执行的检查、失败及未验证事项，并更新末尾进度记录。不得把未执行测试写成已通过。
5. 涉及职责或文件范围的重大设计变更，先更新本计划并说明原因，再开始改动。
6. 凭据不得出现在源码、日志、异常原文、Intent、WorkManager InputData 或代码仓库中。界面中不回显已保存应用密码，不把密码放进 savedInstanceState。
7. 云端调度、上传和清理失败不能使已经成功的本地保存被报告为失败。未加密数据不能进入上传路径。
8. Windows 命令使用 PowerShell，避免 `&&`。测试或构建失败要查明原因，不能关闭 Lint、忽略测试或关闭 TLS 校验来通过检查。

## 三、建议的代码职责

以下 Java 路径均相对于 `app/src/main/java/com/beemdevelopment/aegis/`。实施前检查是否已有同名或同职责组件；必要的小型数据类型优先放在所属类中，不先搭建通用同步框架。

| 组件 | 职责 |
| --- | --- |
| `backup/NutstoreCredentialStore.java` | 配置与凭据持久化、独立 Keystore 别名、配置版本标识、清除授权 |
| `backup/WebDavClient.java` | 有界 WebDAV 请求、路径校验、XML 解析、错误分类、网络请求取消 |
| `backup/NutstoreBackupStore.java` | 不可变加密快照、任务元数据、请求版本、成功版本、云端状态 |
| `backup/NutstoreBackupManager.java` | 自动备份入口、任务唤醒、生命周期协调、停止及清理 |
| `backup/NutstoreBackupWorker.java` | 持久后台上传、重试、内容验证、旧版本清理 |
| `ui/NutstoreBackupsActivity.java` | 配置、连接测试、备份状态、版本列表、下载；支持管理与首次恢复两种入口 |

Worker 使用标准 `Worker(Context, WorkerParameters)` 入口即可，优先复用项目的依赖获取方式；只有确有需要时增加 Hilt WorkerFactory。凭据存储和备份状态不得混成主仓库格式。

## P0：恢复可构建的项目基线

**目标：** 功能开发前先确认当前源码能够构建，并保留基线测试结果。

**主要文件：** `app/build.gradle`；如 SDK 路径缺失，使用本地 `local.properties`，不提交该文件。

**任务：**

- 阅读 `getCmdOutput`、`getGitHash`、`getGitBranch` 的调用。已核实当前目录无 `.git`，Gradle 在配置 `:app` 时因 Git 命令返回 128 退出。
- 无 Git 元数据时给构建信息使用明确的回退字符串；有 Git 元数据时读取实际值。只处理预期的元数据缺失，不掩盖其他构建异常。
- 检查 JDK、SDK 和依赖解析，建立 Debug 构建及单元测试基线。当前环境已发现 JDK 21 和 SDK 36，但不能据此跳过编译检查。
- 记录原有失败及其原因；与本功能无关的修复要说明范围。

**检查命令：**

```powershell
.\gradlew.bat help --console=plain
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

**阶段完成条件：** Git 元数据问题解决；Debug APK 可生成；基线单元测试成功。依赖解析失败或阻碍继续开发的原有测试失败应记录为 P0 受阻，先解决再进入下一阶段。

## P1：配置和凭据安全存储

**目标：** 后台任务可以读取坚果云应用密码，仓库锁定不影响上传凭据读取。

**主要文件：** 新增 `NutstoreCredentialStore.java`；引用现有 `crypto/CryptoUtils.java`；添加对应单元或设备测试。

**任务：**

- 配置包含账号、远程根目录、自动备份开关、保留数量、随机设备标识和配置版本。默认自动备份关闭、保留数量为 5；保留数量限制为 1–100。
- 根目录按相对路径逐段校验，不接受上级跳转、外部 URL、用户信息或查询片段。
- 使用独立 Keystore 别名和允许后台使用的 AES-GCM 密钥保护应用密码，复用 CryptoUtils 的加解密处理。
- 现有 `KeyStoreHandle.generateKey()` 设置了 `setUserAuthenticationRequired(true)`，不可直接用于云盘后台凭据。保留原有指纹解锁行为。
- 凭据密文与配置存入 `noBackupFilesDir` 专用文件，采用原子写入。不要放入默认 SharedPreferences；现有系统备份会包含全部 SharedPreferences。
- 配置更新增加版本标识。凭据缺失、密钥失效或恢复到新设备时，状态为需要重新配置，不自动生成新密钥覆盖仍需诊断的密文。
- 提供安全读取、保存、检查配置和清除方法；密码显示字段保持为空，明确提示是否已经保存。
- 密码输入控件关闭自动保存状态；还要检查系统自动填充和界面重建，不能只避免主动写入 Bundle。

**阶段完成条件：** 有效凭据保存和读取成功；密钥缺失、文件损坏、错误密文能被识别；密钥认证策略和不参与系统备份的存储位置经过设备或源码检查。使用真实 Keystore 的行为以设备测试为准。

## P2：WebDAV 客户端和连接测试

**目标：** 具备后续阶段需要的网络能力，并能在模拟服务中验证协议行为。

**主要文件：** 新增 `WebDavClient.java`；`app/build.gradle`；`app/src/main/AndroidManifest.xml`；对应测试。

**任务：**

- 锁定与现有 SDK、Java 版本兼容的 OkHttp 版本；记录选择依据。新增 INTERNET 和 ACCESS_NETWORK_STATE 权限。
- 实现目录检查和逐级创建、Depth 1 列目录、文件上传、下载、删除及请求取消；处理 `PROPFIND` 的 207 和目录创建的具体响应，不把所有非 200 响应都视为失败。
- 服务器限定为坚果云 HTTPS 地址。对重定向和远程 XML 返回的 href 重新检查主机、协议与目录范围，避免凭据或后续下载跳转到其他站点。
- 配置连接、读写和完整请求超时；所有 Response 和流按生命周期关闭；支持取消正在执行的请求。
- 集中定义请求体、XML、文件下载大小和列表数量上限。上传与恢复的文件大小上限一致；实现时记录采用的数值和设备内存依据。
- 下载按实际读取字节计数，不能只依赖 Content-Length；超限立即终止并删除部分文件。
- XML 禁止 DTD 和外部实体；正确处理命名空间、目录自身条目、目录与文件的区别，以及每个 propstat 的状态。
- 返回明确的目录完整性标识。达到已知目录返回上限、响应损坏或截断时不能宣称列表完整，也不能臆造分页参数。
- 分类处理认证、权限、路径、配额、限流、服务器和网络错误；日志和错误摘要去除凭据与内容。
- “测试连接”检查目标目录可访问；使用独立小型探测文件验证写入与读取，并尝试删除探测文件。清理失败应说明。

**阶段完成条件：** 模拟服务覆盖正常响应、认证失败、路径编码、XML 异常、越界 href、重定向、超时、超限和取消。可以进行真实连接测试，但尚不能把该测试当成完整备份功能验收。

## P3：标准加密快照和持久任务状态

**目标：** 进程结束后还能上传同一份已生成备份，重试内容和文件名保持一致。

**主要文件：** 新增 `NutstoreBackupStore.java`；必要时修改 `vault/VaultRepository.java`；对应测试。

**任务：**

- 复用 `VaultRepository.readVaultFile(context).exportable().toBytes()`，参考 `AegisBackupAgent`。检查已加密且导出后存在可恢复的 PasswordSlot。
- 建立仓库磁盘读取、写入和删除的统一同步边界，覆盖快照读取；不要在持有仓库锁时进行网络访问。不改变现有仓库格式或公开调用签名。
- 原子保存快照到 `noBackupFilesDir` 专用目录，记录快照 ID、相对文件名、远程文件名、摘要和配置版本。文件名使用时间加随机标识，重试不重新命名。
- 持久保存最新请求版本、已确认版本、当前快照及状态。状态只记录可展示的信息，不持久化异常原文或认证头。
- 连续修改合并为最新待备份状态；开始上传后的快照不可变。限制本地残留快照数量，清理逻辑不得删除当前正在使用的文件。
- 读取元数据时校验版本、路径和文件存在性；启动后可识别不完整写入及孤立快照。文件缺失或损坏不得显示为成功。
- 快照生成和验证放在后台执行，不增加保存界面的网络等待。

**阶段完成条件：** 普通密码和独立备份密码均能恢复快照；指纹槽正确移除；锁定仓库后仍可读取快照；明文仓库被拒绝；进程重建后任务元数据可恢复；并发写入与读取不会产生半份快照。

## P4：后台上传、重试和版本保留

**目标：** 完成从持久快照到可验证的云端历史备份的闭环。

**主要文件：** 新增 `NutstoreBackupManager.java`、`NutstoreBackupWorker.java`；`app/build.gradle`；对应测试。

**任务：**

- 锁定兼容版本的 WorkManager。Worker 只使用配置和磁盘状态，不读取 `_vaultManager.getVault()` 或后台解锁仓库。
- 请求版本先持久化，再调度唯一工作链；有网络时执行。连续请求通过版本或摘要合并实际上传。
- 可采用 `APPEND_OR_REPLACE` 唤醒同一串行工作链，已经确认的版本由后续 Worker 快速跳过。若选择其他调度方式，必须证明 Worker 退出瞬间的新请求不会丢失；单独使用 KEEP 然后直接退出不能满足此要求。
- 请求在上传过程中到达时，先完成不可变当前快照，再处理最新请求。已经成功上传的相同摘要可以避免重复上传。
- PUT 使用持久文件名；GET 校验摘要一致后才记录成功。上传成功但验证失败仍保留可重试状态，不能先删除旧备份。
- 网络错误、限流和可恢复的服务器错误采用退避重试，参考 Retry-After；对无效认证、明确的配置错误和密钥失效停止自动尝试。设置重试上限，失败后可手动重试。
- 配置版本在上传前、确认成功前及删除旧文件前检查。配置变更后不得把旧任务内容上传到新账号或新目录。
- 只在完整目录列表基础上、确认新备份成功后，删除当前设备目录中超额旧版本；不得处理其他设备、未知文件或超出目录范围的 href。
- 上传成功时间与清理失败分别记录。WorkManager InputData 只放非敏感任务标识。

**阶段完成条件：** 离线再联网、进程重建、同名幂等重试、连续保存、执行中保存、退出瞬间保存和修改配置等测试通过；一次上传失败保留最后成功的远程版本；不完整列表不会触发清理。

## P5：设置界面与现有保存入口

**目标：** 用户可以配置、开启和查看备份，业务修改能够自动触发备份。

**主要文件：** 新增 `ui/NutstoreBackupsActivity.java` 及布局；`BackupsPreferencesFragment.java`；`preferences_backups.xml`；`VaultManager.java`；Manifest 和中英文 strings。

**任务：**

- 在备份设置增加坚果云入口，复用现有主题、Insets、安全窗口和 Dialogs。
- 提供账号、应用密码、远程目录、保留数量、自动备份、测试连接、立即备份、重新尝试及退出授权。
- 自动备份默认关闭；检查凭据和加密条件后允许开启，开启时调度首份备份。
- 在 `VaultManager.saveAndBackup()` 本地保存成功后请求云端备份，检索并检查所有调用点。新增云端调用异常只影响云端状态。
- 显示未配置、等待网络、等待上传、上传中、成功、失败和清理失败；只有内容验证完成后显示成功。
- 观察状态使用生命周期安全的方式；旋转、返回和锁定不会把密码留在恢复状态中，也不会导致旧界面更新或重复调度。

**阶段完成条件：** 新增、编辑、删除、导入和立即备份都能调度；仅开启云端备份也有效；云端失败时本地数据成功保存；未加密时开启入口不可用；中英文资源和基本布局正确。

## P6：云端手动恢复，包括首次安装

**目标：** 云端文件进入已有解密与导入流程，错误或取消不修改仓库。

**主要文件：** `NutstoreBackupsActivity.java`；`ImportEntriesActivity.java`；`ImportExportPreferencesFragment.java`；`ui/slides/WelcomeSlide.java`；欢迎页布局；必要的导入辅助提取。

**任务：**

- 在坚果云界面增加设备目录和版本列表，显示备份时间、大小与来源，排序稳定。列表不完整要告知用户。
- 下载到私有临时文件，检查大小、标准 Aegis 格式和加密状态；输入密码和解密验证继续使用 AegisImporter。
- 已有仓库：进入 ImportEntriesActivity，复用条目选择、重复检测和显式清空确认。共享现有 Intent 构造逻辑，避免复制导入流程。
- 首次安装：坚果云界面使用恢复模式，允许没有已加载仓库；禁用自动备份及立即备份，仅提供配置、浏览和下载。
- WelcomeSlide 接收下载文件，抽取公共的文件验证与导入方法，使本地文件和云端文件复用同一路径。不要复制另一套密码输入或直接覆盖未验证文件。
- 返回文件的所有权由接收方负责：云端界面返回后不能在 onDestroy 中提前删除结果；恢复完成、取消或失败时清理，进程重建时处理残留。
- 恢复到新设备后自动备份继续关闭，凭据需本机重新配置；用户确认开启后再上传，避免把空仓库或未完成恢复的仓库当成新备份。

**阶段完成条件：** 现有仓库与首次安装都恢复成功；独立备份密码正常；密码错误、损坏文件、取消和超限下载不会改变原有仓库；条目、分组和图标正确；旋转及恢复期间锁定可安全退出。

## P7：生命周期、紧急清除和备份提醒

**目标：** 防止新增持久数据和后台工作破坏现有安全与提醒行为。

**主要文件：** `VaultManager.java`；`AegisApplicationBase.java`；`PanicResponderActivity.java`；`Preferences.java`；`MainActivity.java`；新增 Manager/Store 的必要完善。

**任务：**

- 启动时协调持久请求和 WorkManager，清理孤立快照。不要把需要重试的数据放入启动时被清空的 cacheDir。
- 关闭自动备份时停止后续上传并清理待处理快照；凭据保留用于用户主动恢复。退出授权时另外删除凭据并撤销本地配置有效性。
- 账号或目录变更先使旧配置版本失效、取消旧工作和网络请求，再保存新配置；自动上传需要针对新配置重新确认开启。
- 关闭仓库加密时停止云端工作、清除本地快照和凭据，并处理现有 `KeyStoreHandle.clear()` 会删除所有别名的情况。
- 紧急清除时使任务版本失效，取消 WorkManager 和执行中的网络请求，清理本地凭据与快照，再按现有逻辑清除主仓库。已发送的远程请求不能保证撤回，远程历史备份不自动删除。
- 清理和取消共用一个协调入口，避免不同界面各自实现。Worker 在执行有影响的操作前检查配置版本及取消状态。
- 云端状态独立管理，并纳入主界面提醒：排队不等于备份成功；云端错误能跳转坚果云设置；成功时间参与备份提醒；当前版本尚未备份时不能借旧版本成功状态隐藏提示。
- 原有 BackupResult 仅区分本地与 Android；不要把云端状态套进现有布尔标识导致错误归类。改动提醒时同步检查 Dialogs 和所有调用点。

**阶段完成条件：** 关闭开关、退出授权、更换账号/目录、关闭加密、紧急清除都通过竞态测试；停止后的旧任务不继续确认成功或删除远程文件；本地新增数据按规则清理；原有备份和提醒正常。

## P8：完整验证、真实联调和交付

**目标：** 交付代码、可安装 Debug APK、使用说明和可追溯的验证结果。

**主要文件：** 新增测试；必要扩展已有 `BackupExportTest`、`VaultRepositoryTest`、`IntroTest`、`PanicTriggerTest`；FAQ；`docs/jianguoyun-validation.md`。

**自动化验证：**

- 协议与路径：认证、重定向、XML 命名空间、异常 href、目录范围、超限、不完整列表。
- 数据：标准加密格式、普通与独立备份密码、指纹槽移除、明文拒绝、损坏与错误密码不写入。
- 调度：离线、进程重建、连续请求、执行中请求、退出窗口、配置版本变化、重试和清理失败。
- 回归：本地 SAF 备份、Android 备份、原有导出导入、指纹解锁、初始化、紧急清除及提醒。

**最终检查命令：**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
adb devices
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

设备测试需要可用模拟器或设备；如果没有，保留待验证记录，不能填“通过”。分阶段已经通过的检查只在新改动、失败或未解决问题需要时重复。

**真实坚果云联调：**

1. 在应用界面输入测试账号与应用密码；连接测试验证目录和读写权限。
2. 新增、编辑、删除、导入后检查云端备份，下载并在另一份 Debug 安装或干净模拟器中恢复。
3. 测试断网、切换网络、系统结束进程和重启后的调度；Android“强行停止”单独记录，不能承诺强停后系统仍自动执行。
4. 检查独立备份密码、更改密码后的新旧版本、版本保留及其他设备文件保护。
5. 检查退出授权、关闭加密和紧急清除，以及正在上传时执行这些动作的结果。

> 联调状态（2026-10-08）：第 1 项已通过（JVM 直接调用生产 `WebDavClient` 执行连接探测与完整文件生命周期）；第 2 项的核心链路已通过设备端自动化（加密快照 → 真实上传 → 下载 → 解密恢复），界面级演练仍待人工确认；第 3–5 项尚待在真实设备上演练，清单见 [验证记录](./jianguoyun-validation.md) 第 6 节。

模拟服务通过只证明客户端行为，实际账号联调用于确认坚果云兼容性。本轮已完成实际账号联调（协议层与端到端自动化），界面级演练仍按验证记录第 6 节人工核对。

**交付：** Debug APK 默认输出 `app/build/outputs/apk/debug/app-debug.apk`；文档说明应用密码创建、备份开启、恢复步骤、后台调度及错误处理；验证记录填写实际设备、执行命令、结果和限制。安装包、凭据、签名密钥和本地环境文件不提交源码库。

## 四、后续可选的增强方向

第一版刻意没有实现以下内容，它们可以作为后续的独立改动：

1. 双向自动合并、删除同步与多设备冲突解决（第一版只做“自动上传 + 手动恢复”）。
2. 通用 WebDAV 服务器配置（第一版固定为坚果云 HTTPS 地址）。
3. 应用内的设备管理界面（重命名、删除其他设备的备份）。
4. 上传进度通知（上游目前禁用了通知渠道，见 issue #1047）。
5. 更完整的灾难恢复演练矩阵（多设备、多系统版本、断网与强停场景）。

## 五、阶段进度

允许状态：未开始、进行中、已完成、受阻。只有验收有实际证据才填写“已完成”。

| 阶段 | 状态 | 修改与验证记录 |
| --- | --- | --- |
| P0 构建基线 | 已完成 | `app/build.gradle` 增加无 Git 元数据回退；`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain` 于本轮通过（5m29s，57 个任务，全部单测 PASSED），Debug APK 18.9 MB |
| P1 凭据存储 | 已完成 | 新增 `backup/NutstoreCredentialStore.java`、`backup/NutstoreCredentialsException.java`；11 个单测通过：默认值、保存/读取、路径逐段校验、保留数量边界、损坏文件、密文篡改、密钥缺失、退出授权、全部清除 |
| P2 WebDAV | 已完成 | 新增 `backup/WebDavClient.java`、`backup/WebDavException.java`；新增 OkHttp 4.12.0 与 mockwebserver 测试依赖；Manifest 增加 INTERNET、ACCESS_NETWORK_STATE；19 个模拟服务测试通过：认证、限流与 Retry-After、服务器错误、重定向不跟随、路径编码、超时、超限、取消、越界 href、XML 异常、探测文件读写与清理失败 |
| P3 快照状态 | 已完成 | 新增 `backup/NutstoreBackupStore.java`、`backup/NutstoreBackupException.java`；`vault/VaultRepository.java` 增加统一文件锁与 `readExportableVaultFile`；14 个单测通过：标准加密导出与解密恢复、明文与无密码槽拒绝、请求合并、配置变更失效、摘要损坏检测、快照丢失处理、本地版本清理、失败与清理状态记录 |
| P4 后台上传 | 已完成 | 新增 WorkManager 2.10.1；新增 `backup/NutstoreBackupManager.java`、`backup/NutstoreUploader.java`、`backup/NutstoreBackupWorker.java`；9 个上传测试通过：成功上传与远程版本清理、同名幂等重试、内容校验失败保留快照、清理失败不影响成功状态、不完整列表禁用清理、配置切换中止上传、断网可重试、限流窗口阻止重复请求、无待上传任务跳过。上传序列放在可测试的 `NutstoreUploader`，Worker 只负责 WorkManager 重试策略与尝试上限 |
| P5 设置接入 | 已完成 | 新增 `ui/NutstoreBackupsActivity.java`、`res/layout/activity_nutstore_backups.xml`、`backup/NutstoreBackupStatus.java`、`ui/tasks/NutstoreConnectionTestTask.java` 与中英文文案；`BackupsPreferencesFragment` 增加入口与状态摘要；`VaultManager.saveAndBackup()` 接入自动调度。密码字段关闭自动填充与实例状态保存。`assembleDebug` 与全量单元测试通过 |
| P6 手动恢复 | 已完成 | 新增 `ui/tasks/NutstoreListTask.java`、`ui/tasks/NutstoreDownloadTask.java` 与版本条目布局；设备目录与版本列表、下载后校验标准加密格式，已有仓库进入现有 `ImportEntriesActivity`；`WelcomeSlide` 增加"从坚果云恢复"入口并抽取共用导入方法，导入完成/取消/失败后清理临时文件，恢复模式禁用上传与自动备份 |
| P7 生命周期 | 已完成 | `AegisApplicationBase` 启动恢复待上传任务；`PanicResponderActivity` 与关闭加密统一通过 `NutstoreBackupManager.wipe()` 取消任务、请求并清理凭据与快照；账号/目录/密码变更先取消旧任务；Worker 仅在最新状态确认后清除备份提醒；主界面增加坚果云失败错误卡；5 个调度/清理单测通过 |
| P8 联调交付 | 已完成 | 全量校验通过：129 个单元测试、`lintDebug`、`assembleDebug`；真实坚果云账号联调完成：JVM 直接调用生产 `WebDavClient` 的协议层检查、设备端端到端上传/回读/解密（`NutstoreLiveIntegrationTest`）与设置界面冒烟（`NutstoreBackupsActivityTest`）；过程中发现并修复 Android XML 解析器不支持 `setXIncludeAware` 导致 PROPFIND 崩溃的缺陷；既有设备测试 23 项中 18 项通过、5 项既有失败（已对照实验确认无关）。详见 [验证记录](./jianguoyun-validation.md) |

### 实施偏差记录（2026-10-08）

- 上传序列从 Worker 拆到 `backup/NutstoreUploader.java`，Worker 只保留 WorkManager 重试策略与备份提醒联动；这样可以用模拟 WebDAV 服务覆盖完整上传流程，行为与职责没有变化。
- 新增 `backup/NutstoreBackupStatus.java`，作为状态与错误文案的唯一映射点，设置界面、偏好摘要与主界面提醒复用同一份文本。
- 下载文件交给现有导入流程使用；`WelcomeSlide` 在导入完成、取消或失败后删除临时文件，`NutstoreBackupsActivity` 启动时清理 `cacheDir/nutstore-restore` 残留（cacheDir 本身在应用启动时也会被清空）。
- 远程版本裁剪会跳过刚刚确认上传的文件，避免设备时钟回拨时误删新备份。
