# D01 开发候选组装与独立检查记录

2026-11-11，本机文件仓库生成开发演练坐标 `com.cn.media:android-playback-sdk:1.0.0-rc.1`。下表证据只关联 AAR SHA-256 `059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8`。后续修改须按新摘要取证。用户确定的私库正式坐标版本为 `1.0.0`。

| 检查项 | 结果 | 证据 |
|---|---|---|
| 制品与尺寸 | AAR 为 3,179,406 字节（3.03 MiB），低于 12 MiB 目标；直接 AAR 和从离线 ZIP 再读出的 AAR 摘要相同 | [候选清单](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/public/candidate.json)、[离线仓库](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/public/offline-maven.zip) |
| 元数据、依赖与权限 | POM 为 aar，Gradle API/runtime 指向同一 AAR，两种元数据均无外部运行时依赖；源码和 Javadoc 附件存在。核对五个 Maven 文件的全部校验旁文件与模块元数据引用。AAR 清单最低 API 23，无组件或权限。已有 JNI consumer 规则进入 AAR | [公开材料与摘要](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/public/SHA256SUMS)、[接入方式](21-aar-delivery.md) |
| Native 与符号 | 仅 arm64-v8a，六个 ELF 的 SONAME 与包内名称一致，所有 PT_LOAD 支持 16 KB 边界。按 DT_NEEDED 查找随包库及 API 23 平台 stubs，逐项解析全部强符号；弱导入单列。六份未剥离 ELF 都含 DWARF，逐库用来自实际 AAR 的导出地址还原函数名、文件名与行号 | [逐库报告](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/public/candidate.json)、[私有符号清单](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/private/SHA256SUMS) |
| 来源、许可证与重新链接 | 再次验签 FFmpeg 8.1.3，签署主钥指纹与固定值匹配。使用归档对应的构建脚本、原始源码和 NDK r30 从零交叉编译五库，生成 136 个头文件。将归档中的 Java/C 工程与重建库链接，禁用 Gradle 构建缓存，clean release AAR 成功。两份 AAR 有相同的六个库名，33 个类文件逐字节相同；重建 AAR 的 SHA-256 为 `7e6569b7756abaab8c5627d2ea600f909dd103757722f08a56470a6104634f81`，各库实际摘要单独保留 | [对应源码](../artifacts/distribution/1.0.0-rc.1/059be34f6b49c489-4b23da83daa8/public/corresponding-source.zip)、[重建报告](../artifacts/distribution/checks/059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8/20261111-1701-source-api23/report.json)、[完整构建输出](../artifacts/distribution/checks/059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8/20261111-1701-source-api23/rebuild-lint-clean.log) |
| 最低 Java API 静态检查 | 候选源码运行 Android Lint 8.13.0 `NewApi`，minSdk 23，0 项。隔离副本加入一次未保护的 API 29 调用作为负对照，lint 正确阻断；移除该调用后再次 clean 构建和 lint 通过 | [Lint XML](../artifacts/distribution/checks/059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8/20261111-1701-source-api23/lint-results-release.xml)、[负对照日志](../artifacts/distribution/checks/059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8/20261111-1701-source-api23/api23-control.log) |
| 宿主与其他 native SDK 共存 | 实际构建独立双 AAR 宿主：另一个 JNI 库使用相同名称 `libavcodec.so` 时，AGP 在 mergeDebugNativeLibs 拒绝重复文件。改用 `libplayback_companion.so` 后构建成功，APK 内保留 7 个库，原六库与候选逐字节相同。该报告覆盖 APK 组装 | [构建及 APK 摘要](../artifacts/distribution/checks/059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8/20261111-165725-coexist/report.json)、[重复库诊断](../artifacts/distribution/checks/059be34f6b49c4891983187771112fa776d710a5afd56a9aaa85aba94855fec8/20261111-165725-coexist/duplicate.txt) |

**设备边界**　Android 6.0 / API 23 的 arm64 真机 D02 尚未取得，应用安装、加载、播放与资源回收待实测。arm64 的 16 KB 页环境 D06 也需独立记录 APK 对齐及实际加载、播放。以上 API 23 stubs 和 lint 属静态检查，PT_LOAD 来自二进制检查。

**审核结论**　当前为本地演练候选。D02 独立 Java/Kotlin 宿主四组、D03 授权私库、Q02～Q04、质量与发行双签核按各自记录归档。正式发布与正式消费者回读均未执行。对应源码重建结果用于验证使用者能重新编译和链接。交付和后续宿主验收使用最前面的固定 AAR 摘要。

## 再验证

先按[接入手册](21-aar-delivery.md)生成本地 Maven、AAR、离线、源码及符号包。仓库不需要远端凭据。以下命令分别复验 Java 最低 API 和独立宿主的 native 库路径冲突。把示例 AAR 路径替换成输出目录下的真实文件。

```powershell
.\gradlew.bat --offline --no-build-cache -I tools/assemble-api23-lint.gradle :sdk:lintRelease
python tools/assemble-check-coexist.py <交付目录中的AAR路径>
```
