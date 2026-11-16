# AAR 与 Maven 发行设计

逐项执行与记录见 [D01～D05 发行与接入验收手册](18-release-verification.md)。

## 构建输入与产物

固定 FFmpeg 来源、精确版本及补丁、NDK、Android 构建工具、最低 API、各 ABI 配置与组件清单。FFmpeg 以独立动态库随包分发，JNI 桥封装私有接口。裁剪以验收样本为准，保留解封装器、解析器、必要的码流转换、解码、重采样和转色组件。首版优先 arm64-v8a，x86_64 用于模拟器；32 位设备需求确认后单独构建和验收。

正式 AAR 包含 JVM 类、清单、consumer R8 规则及各声明 ABI 的完整动态库集合。公开接口和核心运行时尽量只依赖 Android/Java 平台，核对编译器引入的运行时依赖。若存在外部依赖，直接 AAR 渠道须随交付包明确提供依赖及版本。保留与制品 Build ID 匹配的未剥离符号文件，和普通运行时制品分开交付。

## 三种渠道

| 渠道 | 交付物 | 独立验证 |
|---|---|---|
| 本地 AAR | Release AAR、SHA-256、接入文档、ABI 和依赖清单、许可证 | 新宿主从本地文件集成，构建混淆 release 并播放音视频 |
| 离线 Maven | 可解压的 Maven 仓库，含 AAR、POM、Gradle Module Metadata、必要依赖及校验清单 | 预置 Android 构建工具后清空 SDK 依赖缓存，断网从仓库按坐标构建 |
| 远程 Maven | 同一 release AAR 和元数据，坐标采用发行方拥有的 groupId、稳定 artifactId 和固定版本 | 在干净宿主拉取指定版本，核对摘要、传递依赖和安装后的 native 加载 |

远端可选择 Nexus、Artifactory 或 Maven Central。正式构建一次，将同一二进制用于三个渠道。私库读写凭据分别管理，发布凭据交由发行环境注入。正式版本不可覆盖；快照使用独立版本或仓库。Central 公开发行前确认命名空间、POM、签名、校验、源码和文档包要求。公开发布须单独授权。[Android 库发布指南](https://developer.android.com/build/publish-library/upload-library) · [Central 发布要求](https://central.sonatype.org/publish/requirements/)

## 发布流程

1. 固化版本、组件清单、API 差异、格式支持表及已知问题。公开 API 不兼容改动使用主版本，新增兼容能力使用次版本，修复使用补丁版本。
2. 生成 release AAR、元数据、源码与文档包、符号档案、许可证和摘要清单。校验每个 ABI 的 ELF 架构、SONAME、依赖链及 JNI 加载名称；测试宿主已有 native SDK 时的命名和 C++ 运行时冲突。
3. 将制品置入隔离暂存仓库。验证 Java 和 Kotlin 空白宿主、R8 release、APK 和由 AAB 生成的实际安装包。检查 native ELF 与 APK 打包对齐，并在 16 KB 页环境运行。[Android 16 KB 页支持](https://developer.android.com/guide/practices/page-sizes)
4. 对照实际构建配置核对 FFmpeg 和外部组件许可证，交付对应源码获取方式、补丁、构建配置与声明。动态链接本身不能替代对实际发行义务的核对。[FFmpeg 官方许可说明](https://ffmpeg.org/legal.html)
5. 审核通过并获得授权后发布。发布后重新下载正式坐标，校验摘要并完成冒烟播放。发现问题时保留原制品，公告受影响版本和可回退版本，使用新补丁版本修复。
