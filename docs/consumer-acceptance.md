# D02～D05 独立宿主验收

候选坐标是 `com.cn.media:android-playback-sdk:1.0.0-rc.1`。验收应用位于 [acceptance/consumer](../acceptance/consumer)，以独立 Gradle 根工程运行。Java 与 Kotlin 宿主有不同的 applicationId，各自调用公开 API 打开、播放、暂停、定位和释放，使用相同的验收步骤。两个宿主均启用 R8，使用本机 Android debug keystore 签名，仅供安装测试。

## 2026-11-13 开发候选

候选 `d6e8ee93` 已通过三种接入方式的 Java/Kotlin 六组构建和五组负例。扩充独立宿主后，两种语言都编译了实际换轨和 stop 调用，六组 R8 宿主构建成功，APK 及拆分安装件的 12 份签名校验通过。使用这批新宿主运行 `verify-consumer-device.py --with-track-switch` 可在 V01、A01 外增加 V05 的双轨切换检查。[11 组构建](../artifacts/consumers/run-20261113-132621-423000/summary.json) · [扩充宿主](../artifacts/consumers/run-20261113-132948-807094/summary.json) · [安装件签名](../artifacts/consumers/run-20261113-132948-807094/signatures.json)

本轮针对 `77494404a06bd18235e1371e289d4eface2be3eb55255ecb8566b53aef4d9271` 单独留证。AAR 为 3,221,534 字节。直接包、离线仓库、对应源码和许可材料可从同一目录取得。[候选清单](../artifacts/distribution/1.0.0-rc.1/77494404a06bd182-ca07896ee35e/public/candidate.json) · [离线仓库](../artifacts/distribution/1.0.0-rc.1/77494404a06bd182-ca07896ee35e/public/offline-maven.zip)

首次 Java-only 宿主启用 R8 时，缺少 SDK 用于标明空值的两个编译期注解，构建失败。修复后的 AAR 随包提供 `org.jetbrains.annotations.NotNull` 和 `Nullable` 的精确消费规则。裸 AAR、离线 Maven、回环 HTTP 三种接入的 Java/Kotlin 宿主全部通过 R8 release 构建。另五个负例覆盖缺失 AAR、native 路径冲突、HTTP 401、HTTP 404 和损坏元数据，均出现预期错误。六个独立 APK 及两个 AAB 的六个拆分 APK 均通过签名验证，两份 arm64 拆分包各有预期的六个库，其 SHA-256、无压缩和 16 KB ZIP 对齐检查通过。[首次失败](../artifacts/consumers/run-20261113-090019-516631/aar-java/gradle.log) · [11 组宿主结果](../artifacts/consumers/run-20261113-090840-929719/summary.json) · [12 份 APK 签名](../artifacts/consumers/run-20261113-090840-929719/signatures.json)

在单独目录解开本轮对应源码，复用已核验的五个 FFmpeg 共享库和头文件，关闭 Gradle 构建缓存重新编译 JNI 和 Java。48 个 class 及资源逐项一致，JNI ELF 仅 Build ID 段不同，API 23 的 NewApi 检查通过。五份 Maven 附件也已从只读回环仓库重取并比对摘要。真机安装及真实私库读端点需按候选摘要另行登记。[源码重建证据](../artifacts/validation/20261113-rc-local/rebuild-s03.json) · [回读证据](../artifacts/consumers/readback-20261113-090841-032716/readback.json)

删去回环服务的 AAR 后，回读工具记录 404；改动 POM 字节后，工具识别 HTTP 200 响应内容与登记摘要不符。另将一份独立 JNI AAR 与播放器同时打包，异名库共存通过；将第二份不同字节的 `libavcodec.so` 加入相同 ABI 时，AGP 按预期报告冲突。[回读缺失](../artifacts/consumers/readback-20261113-091742-547945/readback.json) · [摘要异常](../artifacts/consumers/readback-20261113-091742-614678/readback.json) · [双 AAR 共存](../artifacts/distribution/checks/77494404a06bd18235e1371e289d4eface2be3eb55255ecb8566b53aef4d9271/20261113-090904-coexist/report.json)

## 2026-11-11 开发候选

| 项目 | 已核验的结果 | 证据 |
|---|---|---|
| 候选输入 | AAR 3,179,406 字节，SHA-256 `059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8`。离线 ZIP 的 SHA-256 为 `adecfb836ac6e04270c4d614d9164c39a0ceae2830d6f46706b9cc5914b84430` | [候选清单](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/public/candidate.json) |
| D02 本地与离线 | 裸 AAR × Java/Kotlin、文件 Maven × Java/Kotlin 四组 R8 release APK 构建通过。每个 APK 内六个 arm64 .so 的字节均与 AAR 对应项相同，均未压缩且 ZIP 数据起点按 16 KB 对齐。隔离副本移除 AAR 的负例按预期失败 | [四组解析、编译与负例](../artifacts/consumers/run-20261111-165733-720154/summary.json) · [四份 APK 签名](../artifacts/consumers/run-20261111-165733-720154/signatures.json) |
| D03 消费端回环演练 | 只读 HTTP 提供同一候选，Java 仅取 Gradle Module Metadata，Kotlin 仅取 POM。服务端记录了两种元数据及 AAR 的实际 GET。两种语言均生成 release APK、AAB、按设备选择的 APK 集合。六个 native 库在独立 APK 与 ABI split 中通过摘要和 16 KB ZIP 对齐核查 | [两组构建与安装包清单](../artifacts/consumers/run-20261111-170007-199570/summary.json) · [Java HTTP 记录](../artifacts/consumers/run-20261111-170007-199570/http-module-java/http.json) · [Kotlin HTTP 记录](../artifacts/consumers/run-20261111-170007-199570/http-pom-kotlin/http.json) |
| 安装包签名与选择 | 两份独立 APK 和拆分集合内的六份 APK 使用同一测试证书，通过 apksigner 验证。bundletool 针对本地构造的 API 32/arm64 规格选出 `base-master_2.apk` 和 `base-arm64_v8a.apk`。真机上以设备当轮规格重新选择 | [八份 APK 的签名与摘要](../artifacts/consumers/run-20261111-170007-199570/signatures.json) · [模拟选择日志](../artifacts/consumers/run-20261111-170007-199570/http-module-java/modeled-selection.log) |
| 远程错误处理演练 | 只读本机服务分别模拟未授权 401、依赖路径不存在 404、损坏的模块元数据，Gradle 三例均按预期失败 | [负例结果，含 HTTP 和 Gradle 原始记录](../artifacts/consumers/run-20261111-165733-720154/summary.json) |
| D01 同名 native 库 | 同时接入候选与独立合成的另一份 AAR，两者包含同一 `lib/arm64-v8a/libavutil.so` 路径且字节不同；AGP 在合并阶段准确列出两个输入来源并拒绝构建。这一限制适用于同时捆绑同名 FFmpeg 库的宿主 | [碰撞复验结果](../artifacts/consumers/run-20261111-170628-990587/summary.json) · [AGP 原始错误](../artifacts/consumers/run-20261111-170628-990587/native-collision/gradle.log) |
| D05 只读回读演练 | 从本机只读消费者 URL 下载 AAR、POM、模块元数据、sources、javadoc 五份附件，逐文件比对固定候选 SHA-256，全部相符 | [五份附件的回读记录](../artifacts/consumers/readback-20261111-170628-596598/readback.json) |
| D05 回读异常 | 在隔离副本中移除 AAR，首轮返回 404 并登记待可见；单独更改 POM 字节后收到 HTTP 200，摘要不符即记异常。两种情况都保留原候选 | [AAR 缺失](../artifacts/consumers/readback-20261111-171256-129545/readback.json) · [POM 摘要不符](../artifacts/consumers/readback-20261111-171256-435868/readback.json) |

使用了 JDK 17、Gradle 8.13、AGP 8.13.0、Kotlin 2.1.20、compileSdk 35、minSdk 23。Java 宿主的运行依赖仅有 SDK，Kotlin 宿主自身引入 Kotlin stdlib 及 annotations。每组记录 `gradle-command.json`、`gradle.log`、`http.json`；成功的构建另存 `mapping.txt`、合并清单、APK 和各文件 SHA-256。已保留首次 AAB 转换失败的记录；AGP 自带的 bundletool 1.18.1 需要显式指定已安装 build-tools 的 aapt2，修复后已重新生成两种语言的安装集。

## 用新候选重复验证

先设置 `JAVA_HOME` 和 `ANDROID_HOME`，预置签收的 Gradle 8.13、Android SDK、AGP 和 Kotlin 通用依赖。脚本默认从本机 Gradle 缓存复制只读的工具依赖及元数据，每组新建 Gradle 用户目录和项目缓存。预置清单在 `preloaded-tools.txt`，测试 SDK 命名空间会被排除。断网组使用 `--offline`。HTTP 演练限制 Gradle 网络访问到回环服务器，每组仓库仅允许解析 `com.cn.media`，SDK 的其他来源已排除。

在工程根目录执行下列命令。`$public` 指向待验收候选的 `public` 文件夹。输出目录每轮单独生成，`$run` 取该轮实际输出路径。

```powershell
python tools/verify-consumers.py --offline-zip "$public/offline-maven.zip"
python tools/inspect-consumer-signatures.py --run $run
python tools/verify-maven-readback.py --candidate "$public/candidate.json" --rehearse
```

单组复验可传 `--cases offline-kotlin` 等名称。重复执行时会分别复制交付目录里的裸 AAR 和解压后的离线仓库，两条路径在开跑前必须具有同一摘要。宿主源码按轮冻结并记录各文件摘要。输出的 `modeled-selection.log` 仅在单独执行模拟设备选择时产生。

## 真机验收

### 对封存 AAR 运行功能与长稳套件

使用候选文件夹的 `candidate.json` 生成独立的自测试 APK。工具封存本轮 `sdk/src/androidTest` 的测试类、清单和素材，测试工程的唯一外部依赖是登记摘要的发行 AAR。测试包沿用 `com.cn.media.playback.test`、`InputChecks`、FixtureProvider 及原有设备脚本，安装时会替换同名开发测试包。请在前一轮测量结束后切换。

```powershell
python tools/build-candidate-instrumentation.py --candidate "$public/candidate.json" --output $evidence
$apk = Join-Path $evidence 'test.apk'
.\tools\run-device-tests.ps1 -Serial $serial -Suite q01 -SkipBuild -ApkPath $apk
.\tools\run-format-matrix.ps1 -Serial $serial -ApkPath $apk
```

每轮 `provenance.json` 关联 AAR、classes.jar、测试输入 ZIP、测试 APK 和签名。D8 将候选编译为独立 DEX，工具核对交付的每个 class 均在该 DEX，校验它在最终 APK 内的 SHA-256，并排除测试 DEX 中重复的 SDK 类。包括发行 JNI 在内的六个 .so 与 AAR 逐字节相同且在 APK 内按 16 KB 对齐。设备记录的 `test-apk SHA256` 必须等于同轮 `apkSha256` 才能归入该候选。开发模块的 debug JNI 与发行 JNI 有不同编译选项，旧测试报告继续按原测试 APK 摘要保留。11 月 13 日在中间候选完成了首次组装、签名和逐项验证。[首次制品来源记录](../artifacts/validation/20261113-rc-local/instrumentation-s03/provenance.json)

### 对独立 Java/Kotlin 宿主验收

以下命令使用显式指定的设备。设备工具会在安装前核对该轮 AAR、APK、拆分件及样本摘要。AAB 组会调用 bundletool 读取设备规格，再为该设备选出安装集并执行 `adb install-multiple`。同一候选的四个 D02 组及两个回环组可以按设备档期分开运行。

```powershell
python tools/verify-consumer-device.py --run $run --serial $serial --cases aar-java aar-kotlin offline-java offline-kotlin
python tools/verify-consumer-device.py --run $run --serial $serial --cases http-module-java http-pom-kotlin
```

V01 用 TextureView 检查可见的非单色图案、实际视频后端与音频消费，A01 在无 Surface 下检查音频消费且没有活动视频轨。两者均检查暂停、定位至 1500 ms、恢复后位置前进以及 RELEASED。每组的实际安装清单、解码器、播放计数、定位结果和固件记录在 `device-*/device.json`。音频计数来自设备的播放头，外部声学采集另按 Q01 执行。此处已列出的构建与签名结果独立于设备播放结论。

## 私库接入与正式版本回读

具备目标私库的只读入口、候选清单及对应身份后，可用 `--remote` 对真实 HTTPS Maven 入口执行 Java Module/Kotlin POM 两种 R8 构建及安装包复验。读权限可由环境变量 `PLAYBACK_MAVEN_USER`、`PLAYBACK_MAVEN_PASSWORD` 注入。版本须与候选一致。私库组的 `gradle.log` 记录解析来源、变体和 AAR 摘要；可另保留仓库访问审计日志。

```powershell
python tools/verify-consumers.py --offline-zip "$public/offline-maven.zip" --version $version --remote $readOnlyRepository
python tools/verify-maven-readback.py --candidate "$public/candidate.json" --repository $readOnlyRepository --channel staging
```

获授权正式发行后，回读方将 `--channel` 设为 `published`，使用批准版本的清单和正式消费者入口。每五分钟检查一次，窗口 30 分钟，身份拒绝或摘要不符立即登记异常。工具保留收到的附件与检查记录，所有远程操作均使用 GET。适用的仓库制品签名按该仓库要求另行核验；本轮 APK 所用 debug 签名仅属于验收宿主。本轮真实私库凭据、正式版本回读及双角色发行签核仍待对应环境和记录。
