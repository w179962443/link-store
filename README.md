# URL Keeper

URL Keeper 是一个本地优先的 Android 链接收集 App，目标场景很明确：在手机上看到一篇值得稍后再读的文章时，用最短路径把链接先存下来，不依赖复杂编辑、不强依赖联网，也不把“稍后整理”变成额外负担。

这个项目把保存动作设计成类似聊天发送消息的体验：底部输入框粘贴 URL，点击发送，每一条“消息”就是一条待导出的链接记录。当前目标设备是小米 14 / HyperOS 3 / Android 15，但应用本身的最低支持版本为 Android 8.0（API 26）。

## 核心特性

- 聊天式输入体验：把“保存一个 URL”压缩成粘贴和发送两个动作。
- 本地优先存储：未导出的链接先写入 Room 数据库，不依赖联网。
- 系统分享接入：可从浏览器或其他 App 的分享菜单直接把文本链接送入应用。
- OneTab 导出：导出格式为 `域名 | url`，标题位使用域名。
- 导出即归档：导出成功后自动清空当前待导出记录，便于进入下一轮收集。
- Gitee Gist 可选备份：作为本地数据库之外的额外快照通道。

## 数据安全设计

这个项目优先解决“没导出的 URL 不能丢”这个问题，因此实现上采用了偏保守的写入策略。

- 所有未导出的链接先进入应用私有数据库，由 Room 持久化保存。
- Android 备份规则已覆盖数据库、DataStore 配置和应用内部导出归档目录，便于设备迁移和系统云备份。
- 执行导出时，应用会先写一份内部归档，再写一份公共下载目录文件。
- 只有当导出文件和内部归档都写入成功后，数据库中的待导出记录才会被清空。
- 如果 Gitee Gist 备份失败，本地保存不会回滚；本地成功始终优先于远程同步成功。

需要说明的边界是：公共下载目录中的文件无法被任何普通应用“绝对保证永不被删”，因为用户手动清理、系统策略、第三方清理工具都可能影响公共目录。为降低这个风险，项目额外保留了一份应用内部归档副本。

## 导出格式

导出文件采用 OneTab 兼容的纯文本格式，每行一条记录：

```text
example.com | https://example.com/article
news.site | https://news.site/post/123
```

导出位置：

- 公共目录：`Downloads/UrlChatExports`
- 应用内部归档：`files/export-archive`

## Gitee Gist 备份

应用内提供一个可选的 Gitee Gist 备份开关，用于在每次保存 URL 或导出后，把当前“未导出列表”同步为一份 OneTab 文本快照。

配置项如下：

- `Gitee access token`：必填。
- `Gist ID`：可选；留空时首次会自动创建一个私有 Gist。
- `备份文件名`：默认值为 `url-keeper-onetab.txt`。

设计原则是“本地优先，远程兜底”。因此远程备份失败只会提示，不会阻止本地保存成功。

## 技术栈

- Kotlin
- Jetpack Compose Material 3
- Room
- DataStore Preferences
- OkHttp
- Android Gradle Plugin + KSP

## 项目结构

主要代码分布如下：

- `app/src/main/java/com/example/urlkeeper/MainActivity.kt`：主界面与 Compose UI。
- `app/src/main/java/com/example/urlkeeper/MainViewModel.kt`：界面状态与交互逻辑。
- `app/src/main/java/com/example/urlkeeper/UrlRepository.kt`：保存、导出、备份的核心流程。
- `app/src/main/java/com/example/urlkeeper/data`：Room 数据库实体、DAO、数据库定义。
- `app/src/main/java/com/example/urlkeeper/export/OneTabExporter.kt`：OneTab 文本导出实现。
- `app/src/main/java/com/example/urlkeeper/backup`：Gitee Gist 备份设置与同步实现。

## 构建要求

在构建本项目之前，请先准备以下环境：

- Android Studio。
- JDK 17。
- Android SDK 35。
- 可用的 Android 模拟器或真机。

当前仓库没有提交 Gradle Wrapper 文件，因此命令行构建依赖你本机已经安装并配置好的 Gradle。使用 Android Studio 打开项目时，IDE 也需要能够解析当前 Gradle 配置。

## 如何构建

### 方式一：使用 Android Studio

1. 用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 选择 `app` 模块。
4. 连接设备或启动模拟器。
5. 直接运行 Debug 构建。

如果只是想安装一个可调试版本，这是最直接的方式。

### 方式二：使用命令行

在确保本机 `gradle` 命令可用后，进入项目根目录执行：

```powershell
gradle :app:assembleDebug
```

构建成功后，Debug APK 默认位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果你需要发布版构建，可以执行：

```powershell
gradle :app:assembleRelease
```

注意：当前仓库没有配置签名信息，Release 构建可产物化，但正式分发前仍需要补充签名配置。

## 运行与使用

1. 打开应用。
2. 在底部输入框粘贴一个 URL，点击发送。
3. 链接会作为一条消息显示在列表中，并立即进入本地数据库。
4. 当你准备整理这些链接时，点击右上角导出按钮。
5. 应用会生成 OneTab 文本文件，写入下载目录，并清空当前待导出记录。

如果你希望把浏览器里的链接更快送进应用，可以直接用系统分享菜单把文本分享给 URL Keeper。

## 后续可扩展方向

- 增加按时间段筛选或搜索历史 URL。
- 增加导出历史查看与再次分享。
- 增加手动备份恢复能力。
- 增加 WebDAV、坚果云或私有服务端备份通道。
