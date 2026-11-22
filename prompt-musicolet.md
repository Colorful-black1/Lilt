# Craft Anything 提示词:复刻 Musicolet

> 目标:`base.apk`(Musicolet 6.14.1,包名 `in.krosbits.musicolet`)
> 模式:深度(Deep);建议技术栈:Kotlin + Jetpack Compose + Media3 + Room

---

## 提示词正文(从这里开始复制)

我要你以目标 APK(Musicolet 6.14.1)为参照,在 Android 上复刻一个功能等价的**纯离线本地音乐播放器**。要求行为一致、操作路径一致,但不得复制它的名称、图标、字体、截图和原始文案——所有素材由你自己制作。

### 技术栈(硬性)

- Kotlin + Jetpack Compose,单 Activity + Compose Navigation
- Media3 / ExoPlayer 负责播放,独立的前台播放服务
- Room 做本地库,Repository + Flow 向 UI 供数
- minSdk 26,targetSdk 35,用 Gradle Wrapper,必须能 `./gradlew assembleDebug` 通过
- 不申请 INTERNET 权限,不集成广告、统计、崩溃上报或任何网络 SDK
- 不使用目标 APK 里的 dex 反编译产物、图片、字体、音频或原始文案

### 第一步:先研究,再动手

用 Deep 模式工具把目标摸清,产出 `findings/feature-map.md`,每个结论都要写明证据(类名 / 资源名 / 字符串),不要凭印象。

- `android_components_list` 列出全部组件
- `android_package_show`、`android_resources_read` 看清单、资源、字符串
- `android_class_show` 看关键类的方法与字节码;有 jadx 时用 jadx,有 apktool 时解资源
- 用搜索结果核对界面清单、设置项、字符串

目标 manifest 里已经声明的屏幕(用作核对清单,不要漏):

- 主界面 `MusicActivity`,搜索 `SearchActivity` / `GhostSearchActivity`
- 播放列表 `PlaylistActivity` / `PlaylistEditActivity`,最多播放 `MostPlayedActivity`
- 均衡器 `EqualizerActivity2`,标签编辑 `Tag2Activity`,歌词编辑 `LyricsEditActivity`
- 文件夹排除 `FolderExcluderActivity`,包含文件夹 `IncludedFoldersActivity`
- 设置 `SettingsActivity`,欢迎页 `WelcomeActivity`,关于/FAQ `AboutActivity` / `FAQActivity`
- 迷你播放器 `MiniPlayerActivity`,锁屏 `LockScreenActivity` / `LockScreen`,屏保 `ScreenSaverActivity`
- 小部件 `Widget4X1Adv` / `WidgetService` / `WidgetPrefActivity`
- 播放服务 `MusicService`,媒体浏览 `MediaBrowserServiceImpl`(Android Auto),`CastProvider`
- 音频裁剪 `TrimActivity`,备份恢复 `RestoreActivity`,数据库清理 `DbCleanActivity`,CSV 导出 `EPCsCSVActivity`
- 其余:图标 `IVActivity`、`TaskSaviour`、`SetLcWallActivity`、`FileUtilsActivity`、`RGReadCalcActivity`

### 功能范围(按优先级实现)

**P0 — 曲库**

- 通过 MediaStore 扫描本地音频,支持 mp3 / flac / ogg / opus / wav / m4a / aac
- **按文件夹浏览**(不是只按标签),树状层级 + 面包屑;增量扫描、扫描进度、手动重扫
- 排除文件夹 / 仅包含文件夹两套规则,改完立即重建视图
- 数据层:歌曲、专辑、艺术家、文件夹、播放列表、队列、播放历史、书签/笔记

**P0 — 播放**

- 前台播放服务 + 通知栏控制 + 锁屏控制 + 媒体按键 / 蓝牙耳机按键
- 拔出耳机自动暂停;音频焦点丢失时暂停/ duck;来电等场景恢复策略
- 无缝播放(gapless);播放顺序:顺序 / 单曲循环 / 列表循环 / 随机
- 播放速度与音调、睡眠定时器(到点暂停并淡出)
- **多队列**:多个命名队列(数量上限以目标实测为准),可新建、切换、重命名、删除、从播放列表装载、导出 m3u;队列同名时的"替换 / 合并"策略做成设置项
- 均衡器:多段图形均衡 + 低音增强 + 虚拟环绕 + 前级增益(段数以目标实测为准),内置预设,按输出设备保存,改动即时生效并在重启后保留

**P1 — 内容管理**

- 播放列表:新建、编辑、排序、拖拽、导入/导出 m3u(往返结果一致)
- Most Played 统计与 CSV 导出;最近播放 / 最近添加
- 书签与笔记(按歌曲保存、可跳转)
- 歌词:读取内嵌歌词与 .lrc,编辑并保存
- 标签编辑:标题/艺术家/专辑/音轨号/年份/流派/封面,支持批量;保存后重扫仍然生效
- ReplayGain:读取已有标签,缺失时计算并可写入
- 搜索:输入即过滤,覆盖歌曲/专辑/艺术家/文件夹/播放列表

**P2 — 界面**

- 首页:底部双 Tab(**文件夹 / 播放列表**)+ 常驻播放条;播放条点开为全屏播放页
- 全屏播放页:封面、标题、进度条、上一首/播放/下一首、随机/循环、更多菜单(均衡器、睡眠定时、速度、标签、歌词、加入队列)
- 列表:字母索引快速滚动、多种排序、多选批量操作
- 主题:浅色 / 深色 / 纯黑,强调色可选;跟随系统
- 迷你播放器(悬浮)、锁屏界面、屏保(全屏封面)、桌面小部件 4x1 / 2x2、快捷设置磁贴
- 多语言:至少中文与英文,文案可切换

**P3 — 有余力再做**

- Android Auto(MediaBrowserService)、Chromecast、音频裁剪、备份/恢复(设置+播放列表)、数据库清理

### 验收标准(每轮都要能自测)

1. 冷启动 → 授权媒体权限 → 扫描到本地音频 → 文件夹视图正确显示;断网可用
2. 播放/暂停/上一首/下一首/拖动进度,连续切歌不爆音不卡顿
3. 通知栏与锁屏的控制按钮真实生效;拔掉耳机后暂停;音量键与媒体键响应正确
4. 建两个命名队列并来回切换,队列导出 m3u 再导入,内容完全一致
5. 均衡器推子实时生效,杀进程重开后设置仍在
6. 睡眠定时器到点暂停
7. 编辑标签后重扫,列表显示新值;封面能更新
8. 在 1080×1920 与 720×1280 两种尺寸下,布局不重叠、文字不截断、播放条不遮挡列表

### 工作方式

- **第一轮只做 P0**,把"扫描 → 浏览 → 播放 → 队列 → 通知栏"这条主链路做通并自测,不要一次铺开
- 每轮开头先读 `findings/feature-map.md`,只改本轮范围内的模块
- 每完成一个模块:构建 → 单元测试 → 安装到设备/模拟器 → 截图,证据放进 `checks/round-N/`
- 行为拿不准时回到目标取证(工具 / jadx / UI 层级),不要猜
- 每轮结束输出:完成了什么、哪些验收项通过、剩余差距、需要我决策的问题
- 遇到目标 APK 有而我没列出的重要行为,先记进 feature-map,再问我是否纳入本轮

### 不要做

- 不要复制目标 APK 的 dex、图片、字体、音频、名称、图标、包名
- 不要申请 INTERNET 权限,不要加任何联网或统计组件
- 不要为了"看起来像"而写死假数据;所有列表必须来自真实扫描结果
- 不要在未通过构建和自测的情况下宣告模块完成

## 结束

## 提示词正文(到这里结束)

---

## 使用建议

- **轮次**:Craft 的 `--rounds` 建议 2~3;第一轮结束先自己装到手机上走一遍主链路,再用"继续"补 P1。
- **后端**:本地 magpie 模型看不见截图,P0 阶段问题不大,但 P2 的界面还原建议换 Claude Code 或装 jadx/apktool 后用文本化的 UI 层级当证据。
- **跟进提示词示例**:
  - "现在实现 P1 的播放列表与 m3u 导入导出,完成后按验收标准第 4 条自测并截图。"
  - "按 feature-map 补齐均衡器,设置项要与目标一一对应,写清每一项的证据来源。"
  - "处理验收标准第 8 条:两种分辨率下的布局问题,先截图再改。"
