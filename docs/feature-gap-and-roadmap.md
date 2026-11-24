# Lilt 功能差距与后续计划

- 状态：草案 v0.1（待评审）
- 日期：2026-11-22
- 基线提交：`3bba1d4 feat: keep console hidden unless developer mode is enabled`
- 仓库：`E:\Codeing\ai-workspace\musicolet_craft`
- 范围：本地优先音乐播放器，不引入在线曲库、推荐、账号、云同步或统计 SDK；歌词获取是可选联网能力，可关闭

---

## 1. 目标与边界

### 1.1 目标

1. 让 Android 版在曲库、播放设置、内容管理上接近 PC 版。
2. 以酷狗的**本地音乐能力**为参照，补齐真正影响日常使用的功能。
3. 保持两端一致的交互语言、设置项命名和验收方式。
4. 本地播放、扫描和管理始终离线可用；在线歌词是可选的联网增强，不得阻塞播放。

### 1.2 明确不做

以下功能即使酷狗有，也不纳入本项目：

- 在线搜索、在线试听、下载
- 每日推荐、私人 FM、排行榜、歌单广场
- 在线封面匹配
- 账号登录、云盘、云同步、跨设备同步
- 评论、分享、K 歌、直播、MV
- 听歌识曲
- 广告、统计、崩溃上报
- 除可选歌词获取以外的联网能力

本地歌词、本地封面、本地 CSV、本地备份不在排除范围内。

在线歌词（LRCLIB/手动 AI）是 2026-11-24 明确加入的可选能力：默认开启但可在设置关闭，
只发送歌名、艺术家、专辑和时长，不发送音频或路径；细节见 `docs/online-lyrics.md`。

---

## 2. 当前实现概览

### 2.1 PC 版（Rust + egui/eframe + Rodio）

已实现：

- 文件系统扫描，多格式，层级文件夹、面包屑、排除/包含规则、隐藏目录、最短时长、启动扫描、扫描进度。
- 歌曲、专辑、艺术家、文件夹、播放列表、收藏筛选、多选、排序、字母索引。
- 搜索歌曲/专辑/艺术家/文件夹/播放列表，带搜索历史。
- 播放列表增删改、上下移动、M3U 导入导出。
- 20 个命名队列、队列位置记忆、队列间跳转、同名替换/合并、M3U。
- 顺序/列表循环/单曲循环/随机，10 秒上一首规则可配置。
- 无缝播放、播放淡入、暂停淡出、0.50–3.00× 速度。
- 本地 Symphonia 解码器与内容探测：MP4/M4A（包括后缀误写为 `.mp3`）可播放，损坏或不支持的文件报错而不会关闭程序。
- 睡眠定时：自定义时分、按歌曲数、等当前曲结束、淡出、历史记录。
- 10 段均衡器、22 预设、前级、低音增强、虚拟环绕、平衡、余量建议、按设备保存。
- ReplayGain 读取与应用。
- 内嵌歌词、`.lrc`、歌词编辑、同步高亮。
- 可选在线歌词：首次播放自动查询 LRCLIB，相近版本可选择；找不到时手动 AI 预览/保存；供应商与 Tool_box 星轨链路一致；只发送歌名/艺术家/专辑/时长。
- 标签编辑（含批量）、封面读取与自定义。
- 收藏、书签/笔记。
- 最多播放、最近添加、播放历史、CSV 导出。
- 浅色/深色/纯黑/昼夜/跟随系统主题，5 种强调色，中英界面。
- 声音输出选择与测试音。
- 定位当前播放歌曲：歌曲页标题栏和正在播放浮层均可触发，跳到完整歌曲列表并滚动到当前行。
- 单曲停止：正在播放页一键“本曲结束后停止”，取消已预载的下一首，播完暂停在曲末；切歌自动取消。
- 重复歌曲检测：设置页按规范化标题、艺术家与相近时长分组，标记最佳副本，可试听或打开文件位置；只报告，不删除文件。
- 开发者模式与独立控制台。

尚未实现：

- 真正的音频交叉淡化。
- 批量文件操作（重命名/移动/删除）。
- 音频裁剪、格式转换、CUE 分轨。
- ReplayGain 计算与写回。
- APE、WMA、DSF/DFF 等格式支持。
- 听歌时长、最常听歌手/专辑统计。

### 2.2 Android 版（Kotlin + Compose + Media3 + Room）

已实现：

- MediaStore 扫描，支持 mp3/flac/ogg/opus/wav/m4a/aac/m4b，打开应用自动扫描、失效记录清理、进度显示。
- 文件夹优先浏览、面包屑、递归播放/随机、文件夹计数。
- 包含/排除文件夹规则，修改后立即重建视图。
- 歌曲、专辑、艺术家、收藏、最近添加、最多播放、播放历史、书签页面；专辑/艺术家详情支持播放全部、随机、收藏、队列和播放列表操作。
- 收藏、播放次数（30% 阈值）、最后播放时间、播放历史、书签备注；用户数据与 MediaStore 扫描数据分表，重扫不会丢失。
- 歌曲列表排序：标题、艺术家、专辑、时长、添加时间、播放次数、最后播放、音轨号、文件名，升降序重启后保留。
- 多选批量收藏、加入队列、加入播放列表；播放列表多选移除。
- 标题排序时显示 A–Z 字母索引，点击跳转。
- 顶部栏和歌曲列表标题栏提供“定位当前歌曲”，切回歌曲页并滚动到当前行。
- 播放列表详情与队列详情提供上下移动排序，修改立即写入 Room；队列详情可查看、播放、随机、移除和调整顺序。
- 分组搜索：歌曲、专辑、艺术家、文件夹、播放列表，匹配文本高亮，保留最近 12 条搜索历史。
- Media3 前台 `MediaSessionService`：通知栏、锁屏、耳机/蓝牙按键、拔出耳机暂停、音频焦点。
- 无缝队列、10 秒上一首规则、随机、三种循环模式。
- 20 个命名队列、同名替换/合并、M3U 导入导出。
- 播放列表增删改、M3U 导入导出。
- 本地歌词：Room 存储、LRC 时间轴高亮、纯文本、从本地 `.lrc`/`.txt` 导入。
- 在线歌词：首次播放无歌词时后台查询 LRCLIB；高置信匹配自动写入，相近版本在播放页选择；LRCLIB 找不到时由用户手动点击 AI，预览后保存。Room v9 增加来源/获取时间与获取状态表；AI Key 使用 Android Keystore 加密，只发送歌名/艺术家/专辑/时长，设置中可关闭。
- 歌词偏移与显示：每首歌独立保存偏移（±100/±500 ms、重置，范围 ±60 秒），播放页与编辑页可调，编辑页显示调整后时间；字号与透明度存本机设置并带预览。桌面/悬浮歌词支持按 LRC 行时间双色进度填充，可在设置中自定义已播放/未播放颜色。
- 浮动歌词：现有播放服务内的置顶卡片，显示当前行、偏移、透明度与 18–44 sp 字号；按行时间从左到右填充已播放颜色。可拖动、记忆位置、锁定后隐藏控件并鼠标/触摸穿透，设置中解锁。可选 `SYSTEM_ALERT_WINDOW` 权限；只读取本机已保存的歌词。
- 主屏小部件：RemoteViews 卡片，封面、标题/艺术家、时间、进度与上一首/播放/下一首；播放服务每秒推送进度，控制按钮使用短时 MediaController。设置中可请求固定到主屏。
- 单曲虚拟标签编辑：Room v6 保存标题、艺术家、专辑、年份、音轨号；扫描数据只读，支持恢复扫描标签，播放页和 MediaSession metadata 同步更新。
- 完整睡眠定时：自定义时分、按曲目数、等当前曲结束、淡出、倒计时、取消；定时器在播放服务中运行。
- 播放/暂停淡入淡出，0–4 秒、250 ms 步进。
- 10 段均衡器（DynamicsProcessing/旧版 Equalizer）、22 个桌面预设、前级 Off/Headroom/Boost、峰值余量建议、低音增强、虚拟环绕、左右平衡、按输出设备保存。
- 命名队列位置记忆：定时和暂停时保存索引与位置，点击队列续播，可在设置中关闭。
- 0.50–3.00× 播放速度。
- A-B 循环：播放页设置 A/B 点，服务内 100 ms 计时器在 B 点回到 A，切歌自动清除。
- 失效文件重连：扫描前把消失歌曲的元数据写入 `missing_songs`；设置页列出歌单、队列、收藏、歌词、书签与标签中的旧引用，可从现有曲库选择替代歌曲，Room 事务内改写全部引用。
- 单曲停止：播放页一键在服务内 100 ms 检查，曲末暂停并回到 0:00；切歌自动取消，暂停后恢复播放仍保留设置。
- 重复歌曲检测：设置页按规范化标题、艺术家与相近时长分组，标记最佳副本；可试听，不删除文件。
- 纯黑/深色/浅色/跟随系统主题，5 种强调色，中英界面。

尚未实现：

- 播放列表/队列拖拽排序、队列间自动跳转。
- 内置/同目录 `.lrc` 自动读取、逐字歌词。
- 批量标签编辑、封面编辑。
- ReplayGain。
- 上一首规则设置、无缝播放开关。
- 昼夜主题。
- 播放统计 CSV 导出。
- 拼音首字母索引。
- 快捷设置磁贴、Android Auto、车载模式、锁屏歌词。

---

## 3. 功能对照矩阵

图例：✅ 已实现；△ 部分实现；❌ 未实现。

| 功能域 | PC | Android | 结论 |
|---|---|---|---|
| 扫描与文件夹规则 | ✅ | △ | Android 缺隐藏目录、最短时长、启动扫描开关 |
| 歌曲/专辑/艺术家浏览 | ✅ | ✅ | 列表、详情、播放/随机、收藏/队列/播放列表入口 |
| 文件夹/播放列表浏览 | ✅ | ✅ | 基本对齐 |
| 收藏 | ✅ | ✅ | 歌曲收藏，重扫和重启后保留 |
| 排序/多选/字母索引 | ✅ | ✅ | 9 种排序、升降序持久化、批量操作、A–Z 跳转 |
| 搜索 | ✅ | ✅ | 五类分组、高亮、最近 12 条历史 |
| 播放列表管理 | ✅ | △ | Android 已支持上下移动排序；缺拖拽排序 |
| 命名队列 | ✅ | △ | Android 已有位置记忆、详情页和上下移动；缺队列间自动跳转与拖拽排序 |
| 播放模式 | ✅ | ✅ | 基本对齐 |
| 上一首规则 | ✅ | ❌ | Android 固定 10 秒 |
| 淡入/淡出 | ✅ | ✅ | 0–4 秒，250 ms 步进 |
| 无缝播放 | ✅ | △ | Android 自动无缝，缺开关 |
| 睡眠定时 | ✅ | ✅ | 自定义时分、曲目数、当前曲结束、淡出、服务内计时 |
| 均衡器/音效 | ✅ | ✅ | 22 预设、前级、低音、环绕、平衡、按设备保存 |
| ReplayGain | ✅ | ❌ | Android 缺读取与应用 |
| 歌词 | ✅ | ✅ | PC 本地/内嵌 `.lrc` + 编辑 + 偏移；Android Room + LRC + 导入 + 偏移 + 字号/透明度；两端新增可选 LRCLIB/AI 在线获取 |
| 在线歌词 | ✅ | ✅ | PC 首次播放自动 LRCLIB、候选选择、手动 AI 预览；Android 服务内自动 LRCLIB、候选选择、AI 预览、Room v9 缓存 |
| 标签/封面编辑 | ✅ | △ | Android 已支持单曲虚拟标签编辑；缺批量与封面编辑 |
| 书签/笔记 | ✅ | ✅ | 位置 + 备注，播放页快捷跳转 |
| 播放统计 | ✅ | △ | Android 有历史/最多播放/计数，缺 CSV 导出 |
| 主题/语言 | ✅ | △ | Android 缺昼夜主题 |
| 系统集成 | ✅ | ✅ | PC 托盘/热键/描边双行桌面歌词与浮钮解锁/迷你播放器/SMTC 已完成；Android 单行悬浮歌词、字号缩放、播放页“词”开关/小部件已完成，缺 Auto/磁贴 |
| 备份/恢复 | ✅ | ✅ | 本地 JSON 备份与恢复已完成；PC/Android 格式各自独立，跨平台导入未做 |
| A-B 循环 | ✅ | ✅ | 两端均支持 A/B 点与循环；切歌自动清除 |
| 失效文件重连 | ✅ | ✅ | PC 保留歌单键并可选新文件；Android 用 `missing_songs` 保留元数据并在事务内改写引用 |
| 单曲停止 | ✅ | ✅ | 两端一键在本曲结束后暂停；切歌自动取消 |
| 重复歌曲检测 | ✅ | ✅ | 规范化标题/艺术家/时长分组，最佳副本优先；只报告不删除 |
| 音频工具 | △ | △ | 交叉淡化、批量文件操作、裁剪/CUE/转换仍缺 |
| 桌面/悬浮体验 | △ | △ | PC 已完成酷狗式左右双行桌面歌词、半透明解锁卡片、长歌词自动缩放、解锁态窗口/滚轮等比例缩放、浮钮解锁与迷你播放器；Android 已完成紧凑单行悬浮歌词与无卡片锁定态，缺锁屏歌词 |

---

## 4. Android 对齐清单

### 4.1 P0：曲库与播放器主体验

#### A. 数据模型

新增或扩展：

- `favorite`：歌曲收藏。
- `playCount`：播放次数。
- `lastPlayedAt`：最后播放时间。
- `lyrics` / `lyricsOffsetMs`：歌词缓存与每首歌偏移（Room v7 已加入 `lyrics.offsetMs`）。
- `bookmarks`：位置 + 备注 + 创建时间。
- `tagOverrides`：标题、艺术家、专辑、音轨、年份；封面编辑留给后续轮次。
- 最近添加、最近播放所需的时间字段。
- Room 数据库版本升级与迁移测试。

落库状态（2026-11-23）：`favorite`、`playCount`、`lastPlayedAt`、`bookmarks`、`searchHistory` 已在 Room v3 完成；`lyrics` 在 Room v4 完成；`tagOverrides` 在 Room v6 完成（单曲虚拟覆盖）；歌词偏移在 Room v7 完成（`lyrics.offsetMs`）。歌词字号/透明度是设备级设置，存在 SharedPreferences / PC 的设置字段中。

验收：

- 收藏、播放次数、最后播放时间重启后保留。
- 旧版本数据库升级不丢歌曲、播放列表、队列和设置。
- 播放到设定阈值后播放次数 +1（默认 30%，可设置）。

#### B. 页面与导航

新增：

- 歌曲
- 专辑 + 专辑详情
- 艺术家 + 艺术家详情
- 收藏 / 我喜欢
- 最近添加
- 最多播放
- 播放历史
- 书签与笔记

状态（2026-11-22）：以上页面均已落地；搜索、文件夹、专辑/艺术家与详情页之间可以返回。

验收：

- 每个页面都有空状态、加载态和错误态。
- 专辑/艺术家详情支持全部播放、随机播放、加入队列、加入播放列表。
- 从搜索、文件夹、播放列表进入详情后能正确返回。

#### C. 列表能力

- 排序：标题、艺术家、专辑、时长、添加时间、播放次数、最后播放、音轨号、文件名。
- 升降序切换。
- 多选模式：批量加入队列、加入播放列表、收藏、移除。
- 播放列表和队列上下移动。
- 字母索引/拼音首字母跳转。
- 列表封面显示开关。

状态（2026-11-22）：排序、升降序持久化、多选批量收藏/队列/播放列表、播放列表多选移除、A–Z 字母索引已完成；Round 6-A 补齐了当前歌曲定位、播放列表/队列上下移动和队列详情页；拼音首字母、列表封面开关和拖拽排序未完成。

验收：

- 排序结果稳定，重启后保留选择。
- 多选操作不会误删文件，只影响曲库/列表关系。
- 播放列表和队列顺序持久化。

#### D. 播放器

- 收藏按钮。
- 歌词面板：内嵌歌词、`.lrc`、纯文本；同步高亮按当前进度滚动。
- 睡眠定时：自定义时分、按歌曲数、等当前曲结束、淡出开关、历史记录。
- 播放淡入/暂停淡出：0–4000 ms，250 ms 步进。
- 上一首行为：总是上一首 / 10 秒后重播。
- 无缝播放开关。
- 播放速度保持 0.50–3.00×。
- 播放位置恢复。

验收：

- 歌词在 720×1280 和 1080×1920 下不遮挡播放控件。
- 睡眠定时到点暂停并淡出，杀进程后设置不残留。
- 淡入淡出不产生爆音。
- 上一首行为切换立即生效。

#### E. 设置

- 扫描隐藏目录。
- 最短时长过滤。
- 启动时扫描开关。
- 队列位置记忆。
- 队列结束后跳转下一队列。
- 均衡器：前级、低音增强、虚拟环绕、平衡、余量提示。
- 按输出设备保存均衡器配置。
- ReplayGain 音量。
- 昼夜主题。
- 显示列表封面开关。

验收：

- 每个设置项都有中英文文案。
- 修改后立即生效并持久化。
- 低端设备上均衡器切换不卡顿、不崩溃。

#### F. 搜索

- 分组结果：歌曲、专辑、艺术家、文件夹、播放列表。
- 搜索历史，可清空。
- 输入即过滤。
- 高亮匹配文本。

状态（2026-11-22）：五类分组、输入即过滤、高亮匹配、最近 12 条搜索历史与清空已完成；文件夹结果当前直接播放该文件夹，不跳转到文件夹页。

验收：

- 输入任意关键词能覆盖五类结果。
- 搜索历史最多保留固定条数。
- 清空搜索后回到原页面状态。

### 4.2 P1：内容管理

- 标签编辑：单曲和批量。单曲虚拟覆盖已完成（Room v6）；批量编辑仍待做。
- 封面选择：内嵌封面、本地图片、恢复默认。
- 歌词编辑：纯文本/LRC，保存到本地。
- 歌词显示：每首歌偏移、字号与透明度（已在 Round 6-C 完成）。
- Most Played CSV 导出。
- 备份/恢复：设置、播放列表、队列、收藏、标签覆盖（Round 7-B 已完成）。
- 导入/导出 M3U 的往返一致性测试。
- 批量加入/移除播放列表。

状态（2026-11-23）：单曲虚拟标签编辑已完成，修改后列表、搜索、专辑/艺术家分组、播放页和 MediaSession metadata 同步更新；歌词偏移、字号与透明度也已落地；本地备份/恢复已在 Round 7-B 完成。批量编辑和封面选择仍待做。

验收：

- 标签修改后列表、播放页、通知栏同步更新。
- 批量编辑失败时给出明确错误，不部分写入。
- CSV 字段与 PC 版一致。
- 备份文件可在同版本或新版本恢复。

### 4.3 P2：Android 系统能力

- 4×1 / 2×2 桌面小部件（Round 7-A 已完成）。
- 快捷设置磁贴。
- Android Auto `MediaLibraryService`。
- 车载/驾驶模式。
- 悬浮歌词（Round 7-A 已完成，需要可选的悬浮窗权限）。
- 锁屏歌词（通知或自定义锁屏页）。
- Chromecast（局域网投屏，可选）。

验收：

- 小部件播放/暂停/下一首真实生效。
- Android Auto 下能浏览文件夹、专辑、播放列表并播放。
- 悬浮歌词可关闭，关闭后不影响通知栏控制。

---

## 5. 以酷狗为参照的本地功能增量

说明：这里只列本地能力；“价值/成本/优先级”是建议，不是既定结论。

### 5.1 歌词体验

| 功能 | 价值 | 成本 | 优先级 |
|---|---|---|---|
| 本地 `.lrc` + 内嵌歌词 | 高 | 低 | P0 |
| 歌词同步高亮 | 高 | 中 | P0 |
| 在线歌词（LRCLIB） | 高 | 中 | P1 |
| AI 兜底（手动预览） | 中 | 中 | P2 |
| 歌词偏移调整 | 高 | 低 | P1 |
| 字体大小/颜色/透明度 | 中 | 低 | P1 |
| 双语/翻译行 | 中 | 中 | P2 |
| PC 桌面歌词浮窗 | 高 | 中 | P1 |
| Android 悬浮歌词 | 高 | 中 | P2 |
| Android 锁屏歌词 | 中 | 高 | P2 |

状态（2026-11-24）：歌词偏移调整、字号与透明度已完成（PC/Android 播放页、编辑页与设置页）；PC 桌面歌词浮窗已在 Round 6-D2 完成，并在后续修复中改为酷狗式左右双行、解锁半透明卡片、锁定全透明、文字描边、长歌词自动缩放完整显示，带可点击小锁浮钮、收藏/上一首/下一首控制及播放页“词”快捷开关；解锁态已支持拖动窗口或滚轮无级缩放，歌词、工具栏按钮与图标一起放大缩小，不设固定最小/最大尺寸，并在可见卡片边缘提供标准双向缩放光标；Android 悬浮歌词已在 Round 7-A 完成，后续改为紧凑单行、锁定时无卡片、设置页字号缩放与播放页“词”开关。在线歌词已在 Round 8-A/8-B 完成：LRCLIB 自动获取、候选选择、手动 AI 预览、缓存与 Key 加密；歌词进度染色与自定义已播放/未播放颜色已在 Round 8-C 完成（PC 桌面歌词、Android 悬浮歌词）；PC 锁定态解锁按钮已放大为带文字的胶囊按钮；文件名标签兜底、AI 歌词提示词/开发者日志与 LRCLIB null 容错已在 Round 8-D 完成；PC 正在播放页已改为歌词优先的两栏布局，Android 播放页改为居中当前/下一行并可点击进入全屏歌词；双语/翻译行和 Android 锁屏歌词仍未做。

技术说明：

- KRC 是酷狗私有格式，不建议作为核心依赖；优先 LRC、内嵌歌词，必要时支持 VTT。
- PC 已用独立透明置顶窗口实现桌面歌词（Round 6-D2）。
- Android 悬浮歌词已在 Round 7-A 完成，使用可选的 `SYSTEM_ALERT_WINDOW` 权限；关闭时不影响播放。
- Android 锁屏没有通用歌词接口，建议先用大号媒体通知或自定义锁屏页。

### 5.2 PC 桌面集成

| 功能 | 价值 | 成本 | 优先级 |
|---|---|---|---|
| 系统托盘图标 | 高 | 低 | P1 |
| 关闭到托盘/最小化到托盘 | 高 | 低 | P1 |
| 全局热键/媒体键 | 高 | 中 | P1 |
| Windows SMTC | 高 | 中 | P1 |
| 任务栏缩略图按钮 | 中 | 低 | P2 |
| 迷你播放器 | 高 | 中 | P1 |
| 桌面歌词 | 高 | 中 | P1 |

建议默认热键：

- `Ctrl+Alt+Space`：播放/暂停
- `Ctrl+Alt+Left`：上一首
- `Ctrl+Alt+Right`：下一首
- `Ctrl+Alt+Up/Down`：音量
- `Ctrl+Alt+L`：桌面歌词开关

状态（2026-11-23）：系统托盘、关闭/最小化到托盘、6 组全局热键、桌面歌词、迷你播放器和 Windows SMTC 均已在 Round 6-D1/6-D2 完成。Ctrl+Alt+L 现在开关桌面歌词窗口；任务栏缩略图按钮仍待做。

### 5.3 Android 系统集成

| 功能 | 价值 | 成本 | 优先级 |
|---|---|---|---|
| 4×1 / 2×2 小部件 | 高 | 中 | P2 |
| 快捷设置磁贴 | 中 | 低 | P2 |
| Android Auto | 高 | 高 | P2 |
| 车载/驾驶模式 | 中 | 中 | P2 |
| 悬浮歌词 | 高 | 中 | P2 |

状态（2026-11-23）：RemoteViews 主屏小部件与悬浮歌词已在 Round 7-A 完成。快捷设置磁贴、Android Auto、车载模式和锁屏歌词仍未做。

### 5.4 音频增强

| 功能 | 价值 | 成本 | 优先级 |
|---|---|---|---|
| 真正的交叉淡化/智能过渡 | 高 | 高 | P1 |
| A-B 循环 | 中 | 低 | 已完成（Round 7-C） |
| 单曲播放（播完停止） | 中 | 低 | 已完成（Round 7-D） |
| 变速与变调 | 中 | 中 | P2 |
| ReplayGain 计算与写回 | 中 | 中 | P2 |
| 音量标准化 | 中 | 中 | P2 |
| 按设备保存均衡器 | 高 | 中 | P1 |
| 低音增强/虚拟环绕/清晰度 | 中 | 中 | P2 |

### 5.5 曲库整理工具

| 功能 | 价值 | 成本 | 优先级 |
|---|---|---|---|
| 重复歌曲检测 | 高 | 中 | 已完成（Round 7-D，只报告不删除） |
| 失效文件定位/重新关联 | 高 | 中 | 已完成（Round 7-C） |
| 批量重命名/移动/删除 | 高 | 高 | P2 |
| 按标签重命名文件 | 中 | 中 | P2 |
| 批量封面/标签编辑 | 高 | 中 | P1 |
| CUE 分轨 | 中 | 高 | P3 |
| 音频裁剪/铃声制作 | 中 | 中 | P3 |
| 格式转换 | 中 | 高 | P3 |
| 本地备份/恢复 | 高 | 低 | 已完成（Round 7-B） |

### 5.6 统计与可用性

| 功能 | 价值 | 成本 | 优先级 |
|---|---|---|---|
| 听歌时长统计 | 中 | 低 | P2 |
| 最常听歌手/专辑 | 中 | 低 | P2 |
| 搜索历史 | 中 | 低 | P1 |
| 拼音/首字母搜索 | 中 | 中 | P2 |
| 按码率/时长/添加时间排序 | 中 | 低 | P1 |
| 收藏/最近添加/常听快捷入口 | 高 | 低 | P1 |

### 5.7 格式支持

| 格式 | 价值 | 成本 | 建议 |
|---|---|---|---|
| APE | 中 | 高 | P3，需要额外解码器 |
| WMA | 低 | 中 | P3，按用户需求决定 |
| DSF/DFF | 低 | 高 | P3，需 DSD 解码 |
| CUE | 中 | 高 | P3，需要索引解析和虚拟音轨 |
| ALAC/M4A | 中 | 低 | 已完成（M4A/MP4 与内容探测；DRM 加密文件仍不支持） |

---

## 6. 技术约束与实现建议

### 6.1 Android Scoped Storage

- 不建议申请 `MANAGE_EXTERNAL_STORAGE`。
- 标签写回优先使用 MediaStore 的写请求或 SAF 目录授权。
- 在写回能力完成前，先使用 Room 内的“虚拟标签覆盖”，保证界面立即可用且不破坏原文件。
- 批量标签编辑要一次性请求授权，失败时回滚，不做部分写入。

### 6.2 Android 歌词文件

- 内嵌歌词：用 MediaMetadataRetriever/标签库读取。
- 同目录 `.lrc`：需要 SAF 目录授权；未授权时只显示内嵌歌词和 Room 缓存。
- 歌词偏移按歌曲存 Room（v7 `lyrics.offsetMs`）；字号、透明度等设备级显示设置存 SharedPreferences。

### 6.3 Android 小部件与 Auto

- 小部件：已用 RemoteViews（Round 7-A）；播放服务推送状态，上一首/播放/下一首通过短时 MediaController，封面异步解码。
- 悬浮歌词：已在现有 `PlaybackService` 内用 `WindowManager` + `TYPE_APPLICATION_OVERLAY` 实现；可选 `SYSTEM_ALERT_WINDOW`，锁定后 `FLAG_NOT_TOUCHABLE`。
- 快捷设置：`TileService`（未做）。
- Android Auto：`MediaLibraryService` + 浏览树；现有 `MediaSessionService` 可复用播放部分（未做）。
- 车载模式：大按钮、少层级、驾驶中减少操作（未做）。

### 6.4 PC 桌面集成

- 托盘：已用 `tray-icon` 0.26（Windows 专用依赖）；图标由 `src/icon.rs` 自绘 32×32 RGBA，窗口和托盘共用。
- 全局热键：已用 `global-hotkey` 0.10；默认 6 组，可在设置中关闭，注册冲突会在设置页显示。
- 隐藏窗口：Windows 下 winit 不处理隐藏窗口的常规重绘，托盘/热键回调会向主窗口投递 `WM_PAINT` 唤醒更新；播放、扫描、睡眠定时或待保存时另有 100 ms 心跳。
- 安全兜底：只有确认托盘图标已注册时才允许关闭/最小化到托盘，避免窗口隐藏后无法找回。
- 媒体键/SMTC：已用 `windows` crate 的 `SystemMediaTransportControls`，通过主窗口句柄注册；元数据、播放状态、时间轴、随机/循环和播放/暂停/上一首/下一首按钮与主窗口同步。
- 桌面歌词：egui deferred viewport，无边框、置顶、可拖动/缩放；当前行左上、下一行右下，使用现有每首歌词偏移与透明度。每行单行显示，字号按窗口宽高自动缩小，避免换行和裁剪。解锁态用 `LWA_ALPHA`（215/255）加圆角窗口区域实现半透明深色卡片，并绘制 44 px（窄窗口 38 px）工具栏；锁定态切换黑色 `LWA_COLORKEY`，只保留描边歌词。Windows 用 `WM_NCHITTEST -> HTTRANSPARENT` 实现锁定穿透，小锁浮钮、托盘菜单或设置页可解锁；解锁态工具栏支持收藏、上一首、播放/暂停、下一首、打开播放器、锁定和关闭。
- 迷你播放器：独立置顶小窗口，封面、标题/艺术家/专辑、上一首/播放/下一首、随机/循环、进度拖动。
- 首次显示位置：桌面歌词默认在屏幕底部居中，迷你播放器默认在右上角；同一会话内关闭/重开会记住拖动后的位置和尺寸。

### 6.5 交叉淡化

- Rodio 没有现成交叉淡化，需要双播放器或自定义混音。
- 先实现固定时长交叉淡化，再考虑“智能过渡”。
- 与现有播放计数阈值解耦，避免 `crossfade_seconds` 继续只影响统计。

### 6.6 格式与 CUE

- Symphonia 不覆盖 APE/DSD。
- 可选方案：引入 FFmpeg/额外解码器，或调用系统解码能力。
- CUE 需要解析索引并映射到单个大文件的偏移，播放/拖动/切歌都要适配。

### 6.7 备份/恢复

- 建议使用单个 JSON + 可选 SQLite 备份。
- 包含：设置、文件夹规则、播放列表、队列、收藏、书签、标签覆盖。
- 不包含音频文件本身。
- 恢复时做版本兼容和冲突处理。
- 状态（2026-11-23）：已按单个 JSON 在 PC 与 Android 实现（Round 7-B）；两端格式各自独立，跨平台导入未做。

---

## 7. 建议路线图

### 第 4 轮：Android 曲库对齐（已完成）

交付：

- 收藏、播放次数、最近添加、最多播放、历史、书签。
- 歌曲/专辑/艺术家/收藏页面及专辑/艺术家详情。
- 搜索分组、搜索历史、排序、多选、字母索引。
- Room 1→2、2→3 迁移和单元/仪器测试。

验收：

- 旧数据库升级成功。
- 收藏和播放统计重启后保留。
- 720×1280 与 1080×1920 截图无重叠。
- 结果见 `base/app/checks/round-4/` 与 `base/app/checks/round-4-c/`。

### 第 5 轮：Android 播放器对齐（已完成）

交付：

- 歌词显示、LRC 同步高亮、本地导入与编辑。
- 睡眠定时完整版（自定义时分、按曲目数、等当前曲结束、淡出、取消）。
- 播放/暂停淡入淡出。
- 均衡器增强：22 预设、前级、低音、环绕、平衡、按设备保存。
- 命名队列位置记忆。

验收：

- 歌词滚动与进度一致。
- 睡眠定时三种模式都可用，息屏后仍计时。
- 淡入淡出按设置生效。
- 均衡器配置和队列位置重启后保留。
- 结果见 `base/app/checks/round-5/`。

未纳入本轮：上一首规则设置、无缝播放开关、ReplayGain、播放列表/队列拖拽排序、队列间自动跳转。

### 第 6-A 轮：当前歌曲定位与列表排序（已完成）

交付：

- PC 顶部栏、歌曲页标题栏与正在播放浮层新增“定位”按钮，跳到完整歌曲列表并滚动当前行。
- Android 顶部栏与歌曲列表标题栏新增定位按钮，切换到歌曲页并滚动当前行。
- Android 播放列表详情、队列详情支持上下移动排序，顺序写入 Room 并立即刷新。
- Android 新增队列详情页，支持查看歌曲、从指定行播放、随机、移除和排序。
- 新增 `movedItem` 单元测试，覆盖中间、顶部、底部和越界位置。

验收：

- 列表滚动到当前位置后不再自动抢滚动。
- 排序立即生效，重启后保留。
- 720×1280/1080×1920 无控件重叠。
- 结果见 `base/app/checks/round-6/`。

未纳入本轮：拖拽手势排序、队列间自动跳转、标签编辑（已在 Round 6-B 完成单曲编辑）。

### 第 6-B 轮：Android 虚拟标签编辑（已完成）

交付：

- Room v6 `tag_overrides` 表，按 `mediaId` 保存标题、艺术家、专辑、年份和音轨号。
- 扫描数据保持只读；列表、搜索、专辑/艺术家分组、播放列表、队列和播放 metadata 统一合并覆盖值。
- 每首歌的三点菜单和正在播放页新增“编辑标签”；支持保存与“恢复扫描标签”。
- 编辑当前播放歌曲时同步刷新 Media3 item 与 MediaSession metadata，并恢复播放位置。
- 24 个单元测试、5 个 Room 迁移测试。

验收：

- 保存后列表、搜索、专辑/艺术家分组立即更新。
- 编辑当前歌曲时播放页和 MediaSession 标题同步更新。
- 重启和 MediaStore 重扫后覆盖值保留。
- 恢复扫描标签后还原原始值。
- 结果见 `base/app/checks/round-6-b/`。

未纳入本轮：批量标签编辑、封面替换、写回音频文件。

### 第 6-C 轮：歌词偏移与显示设置（已完成）

交付：

- PC：`SongExtra.lyrics_offset_ms` 按歌曲保存偏移；正在播放页提供 `-500 / -100 / +100 / +500 ms` 与重置，点击歌词行按调整后的时间跳转。
- PC：设置页新增歌词字号（10–24 px）与透明度（30–100%），带预览，应用到正在播放页歌词面板。
- Android：Room v7 给 `lyrics` 表增加 `offsetMs`；播放页和歌词编辑页提供同样的偏移控件，编辑页预览调整后的时间。
- Android：设置页新增字号与透明度滑块和预览；播放页高亮与滚动使用 `position - offset`。
- 统一规则：正值让歌词延后；偏移范围 ±60 秒；纯文本歌词不受偏移影响。
- 桌面 17 个单元测试；Android 25 个单元测试、6 个 Room 迁移测试。

验收：

- 正偏移时同一进度先高亮较早的歌词行，负偏移相反。
- 重启/重扫后每首歌的偏移保留；Android 6→7 迁移不丢歌词。
- 1080×1920、720×1280 下控件不重叠，长歌词可滚动。
- 结果见 `base/app/checks/round-6-c/`。

未纳入本轮：歌词颜色、双语行、桌面歌词、Android 悬浮歌词。

### 第 6-D1 轮：PC 托盘、关闭到托盘与全局热键（已完成）

交付：

- Windows 托盘图标：左键显示/聚焦主窗口，右键菜单为 Show Lilt、Play/Pause、Previous、Next、Quit Lilt；工具提示显示当前曲目。
- 关闭到托盘（默认开）与最小化到托盘（默认关）；托盘图标未注册时自动禁用关闭到托盘。
- 6 组全局热键（默认开，可在设置关闭）：`Ctrl+Alt+Space`、`Ctrl+Alt+Left/Right`、`Ctrl+Alt+Up/Down`、`Ctrl+Alt+L`。
- 隐藏状态下继续播放；曲目切换、睡眠定时、淡入淡出和状态保存由 `WM_PAINT` 唤醒与 100 ms 心跳推进。
- 设置页新增 Desktop integration 卡片，快捷键弹窗新增全局热键段；注册失败会在设置页提示。
- 桌面 21 个单元测试，覆盖托盘菜单映射、6 组热键 ID 唯一、图标像素、旧设置默认值与歌词偏移。

验收：

- 关闭或最小化后窗口隐藏、进程与播放继续；托盘左键可恢复，最小化状态也能正确还原。
- 6 组热键在窗口隐藏时均生效；音量步进 5%。
- 托盘菜单的暂停、上一首、下一首、显示和退出均生效，退出前保存状态。
- 隐藏时睡眠定时能暂停播放，曲目结束能进入下一首。
- 1180×780、720×1280 下设置页无控件重叠。
- 结果见 `base/app/checks/round-6-d1/`。

未纳入本轮：桌面歌词窗口、迷你播放器、SMTC、任务栏缩略图按钮、热键自定义、托盘气泡通知。

### 第 6-D2 轮：PC 桌面歌词、迷你播放器与 SMTC（已完成）

交付：

- 桌面歌词：独立半透明置顶窗口，当前/上一/下一行歌词，使用每首偏移与透明度；可拖动、缩放；锁定后隐藏控件、禁止拖动并鼠标穿透；`Ctrl+Alt+L` 开关。
- 迷你播放器：独立置顶窗口，封面、曲目信息、上一首/播放/下一首、随机/循环、进度条与时间；展开按钮回到完整播放器。
- Windows SMTC：系统媒体弹窗显示曲目/艺术家/专辑、播放状态、时间轴、随机/循环；播放、暂停、上一首、下一首按钮驱动同一播放引擎。
- 托盘菜单新增 Desktop lyrics、Lock/Unlock desktop lyrics、Mini player；主播放条新增迷你播放器按钮；正在播放歌词标题栏新增 Desktop 按钮。
- 设置页新增桌面歌词开关、锁定开关、桌面歌词字号（18–56 px）与迷你播放器开关；SMTC 初始化失败会显示错误。
- 桌面 23 个单元测试，新增桌面歌词高亮偏移、托盘新增菜单映射和时间轴刻度测试。

验收：

- 桌面歌词在 2560×1440 下以 780×176 显示，长歌词不溢出卡片；字号增大时自动减少显示行数。
- 锁定后窗口鼠标穿透，鼠标位置命中下层窗口；后续桌面歌词改造改用 `WM_NCHITTEST` 并保留分层透明，托盘菜单或小锁浮钮可解锁恢复交互。
- 拖动后位置为 `+100, -82`，隐藏再显示仍保持相同位置和尺寸。
- 迷你播放器的播放/暂停、上一首、下一首、关闭与主窗口状态一致；主窗口隐藏到托盘时两个浮动窗口继续显示。
- SMTC 会话可读取 `Glass Horizon / Lumen Field / Aurora Drift` 和 Paused/Playing，脚本触发 Play/Next 后主窗口进入播放并切到下一首。
- 1180×780 与 720×1280 设置页可滚动且无控件重叠。
- 结果见 `base/app/checks/round-6-d2/`。

未纳入本轮：Android 悬浮歌词、任务栏缩略图按钮、SMTC 封面缩略图、桌面歌词双语/颜色自定义。

### 第 7-A 轮：Android 悬浮歌词与主屏小部件（已完成）

交付：

- 悬浮歌词：现有 PlaybackService 内的 WindowManager 置顶卡片，上一/当前/下一行、偏移、透明度、18–44 sp 字号、进度和播放控制；可拖动、位置持久化、显示尺寸变化自动回夹；锁定后隐藏控件并 `FLAG_NOT_TOUCHABLE` 穿透，设置中解锁。
- 主屏小部件：RemoteViews 卡片，封面、标题/艺术家、时间、进度、上一首/播放/下一首；播放服务每秒推送进度，按钮使用短时 MediaController；设置中可请求固定到主屏。
- 设置页新增浮动歌词开关、锁定开关、字号滑块、悬浮窗权限入口与“添加主屏小部件”入口。
- Android 30 个单元测试，新增浮动歌词行窗口、字号夹取、小部件进度/时间和强调色映射；6 个 Room 迁移测试仍通过。

验收：

- 1080×1920 与 720×1280 设置页无控件重叠；悬浮歌词在桌面和播放页上方均正确显示。
- 定时歌词高亮正确，锁定时窗口 flags 含 `NOT_TOUCHABLE`，控制行隐藏；设置解锁后恢复。
- 小部件可播放/暂停和切歌，时间每秒推进；与通知、主界面共用同一 MediaSession。
- APK 仍无 `INTERNET` 权限，新增的 `SYSTEM_ALERT_WINDOW` 仅在用户开启悬浮歌词时需要。
- 结果见 `base/app/checks/round-7-a/`。

未纳入本轮：Android 锁屏歌词、仅歌词小部件、快捷设置磁贴、Android Auto、车载模式。

### 第 7-B 轮：本地备份与恢复（已完成）

交付：

- PC：版本化 JSON 备份，包含设置、曲库根目录与包含/排除规则、播放列表、队列与位置、收藏/播放计数/最后播放位置、书签、歌词与偏移、标签覆盖、EQ 配置和最近搜索；恢复前校验 app/format/platform，恢复后停止播放、保存状态并重扫。
- Android：通过系统文档选择器导出/导入 JSON；包含歌单、队列、收藏、历史、书签、歌词、标签覆盖、文件夹规则、搜索历史、EQ 与设置。恢复在一个 Room 事务内替换用户表，曲目引用按 mediaId → 文件夹+文件名 → 标题/艺术家/时长回退解析；无法匹配的引用跳过并计数。
- 两端设置页新增“备份与恢复 / Backup & restore”卡片；导出成功/失败、恢复确认、恢复结果都有明确提示。
- PC 29 个单元测试（新增 6 个备份往返、版本拒绝、设置夹取、ID 归一化与根目录回退测试）；Android 37 个单元测试（新增 7 个 JSON 往返、平台/格式拒绝、设置夹取、曲目引用解析、预览与 EQ 测试）；6 个 Room 迁移测试仍通过。

验收：

- Android 实测：9,687 字节 JSON 导出成功；清空歌单、队列、收藏、歌词、书签、标签和搜索后，恢复为 1 个歌单（3 首）、1 个队列（2 首）、1 首收藏、歌词偏移 250 ms、1 个书签和 1 个标签覆盖，设置中的 accent/speed/字号恢复为 sage/1.25×/17.5 sp。
- 备份文件不包含音频文件和扫描索引；Android 恢复使用媒体表当前行做引用解析，不写入扫描数据。
- APK 仍无 `INTERNET` 权限；备份通过 SAF 文档选择器完成，未新增存储权限。
- 结果见 `base/app/checks/round-7-b/`。

未纳入本轮：PC 与 Android 之间的跨平台导入、云端同步、音频文件打包、批量编辑冲突策略。

### 第 7-C 轮：A-B 循环与失效文件重连（已完成）

交付：

- PC：正在播放页新增 A-B 循环控件。第一次按下设 A，第二次设 B，第三次关闭；B 点到达时跳回 A，手动跳转到区间外或切歌会关闭循环。播放循环在现有 LiltApp 心跳中判断，不新增线程。
- Android：播放页新增 A、B、清除控件；循环状态通过 MediaSession 自定义命令交给 PlaybackService，服务内 100 ms 计时器负责回跳，息屏或退到后台仍有效；切歌自动关闭。
- PC 失效文件重连：扫描后保留播放列表中暂时找不到文件的键；设置页与歌单页显示失效数量，列表给出路径和引用来源，使用系统文件选择器选择新文件，校验收音库根目录，移动 SongExtra（收藏/歌词/书签/播放次数）并立即刷新曲库。
- Android 失效文件重连：Room v8 新增 `missing_songs`；扫描删除旧 `songs` 行前保存标题、艺术家、专辑、文件夹、文件名与时长；设置页新增“失效文件”入口，列表展示歌单/队列/收藏/歌词/书签/标签引用，可在曲库中搜索替代歌曲；Room 事务内改写 playlist_songs、queue_songs、song_state、lyrics、bookmarks、tag_overrides、history 并删除旧 missing 行。
- PC 32 个单元测试；Android 41 个单元测试与 7 个 Room 迁移测试。

验收：

- Android 实测：向 `songs` 写入一条不存在的曲目并加入歌单“Missing Test”，同时加入收藏、歌词与书签；扫描后 `songs` 行被移除、`missing_songs` 保留元数据，歌单引用仍在。设置页显示 1 个失效文件，重连到 `Glass Horizon` 后，歌单、收藏（9 次播放）、歌词（+250 ms）和书签全部转到新 mediaId，`missing_songs` 清空。
- Android A-B 实测：`Glass Horizon` 设 A 0:10、B 0:14，连续 5 秒以上采样位置保持在 0:10–0:14；播放页同时显示 A/B 时间与清除按钮。
- PC 截图：1180×780 正在播放页显示 `A-B 0:10 – 0:16` 与清除按钮；设置页显示 `1 missing file(s)`，歌单页显示 `2 tracks · 1 missing`；失效文件对话框列出标题、路径、引用来源并可定位。
- 两端仍无网络权限；Android 备份/重连均不需要新增存储权限。
- 结果见 `base/app/checks/round-7-c/`。

未纳入本轮：真正的交叉淡化、批量重命名、音频裁剪、CUE、格式转换、跨平台备份导入。

### 第 7-D 轮：单曲停止与重复歌曲检测（已完成）

交付：

- PC 单曲停止：正在播放页新增“本曲结束后停止”。开启时丢弃已预载的下一首，播完当前曲后暂停并保存位置；手动上一首/下一首、选择新歌或新队列自动取消。按钮再次按下可取消。
- Android 单曲停止：播放页新增“本曲结束后停止”。状态通过 MediaSession 自定义命令交给 PlaybackService，服务内 100 ms 计时器在曲末 180 ms 前暂停并回到 0:00，切歌自动清除；暂停后恢复播放仍保留设置。播放页显示“已在本曲结束后停止”状态。
- 重复歌曲检测：PC 与 Android 都按规范化标题（忽略大小写、空格与标点）、艺术家和 ±2 秒时长分组；PC 设置页“查找重复歌曲”，Android 设置页“重复歌曲”。每组把最高码率、最大文件的副本标记为“保留”，列出格式、估算码率、大小、时长与路径；可试听/打开位置。只报告，不删除或移动音频文件。
- PC 37 个单元测试；Android 47 个单元测试与 7 个 Room 迁移测试。

验收：

- PC 实测：`Glass Horizon` 从 0:45.5 开始，开启单曲停止后播到 0:48 暂停，队列的下一首 `Soft Static` 保持未播放。
- Android 实测：3 秒 `Glass` 位于包含 `Glass Horizon` 的搜索队列中；开启单曲停止后 5 秒仍停在 `Glass` 的 0:00 / 0:03，页面显示“已在本曲结束后停止”，未进入下一首。
- Android 重复检测：向 `Music/LiltTest` 复制 `05 - Glass Horizon copy.mp3` 后重扫，设置页显示 `1 group · 2 files`，检测页把 `01 - Glass Horizon.mp3` 标记为保留，并同时列出两个文件的 24 kbps、59 KB、0:20 与路径。
- PC 截图：1180×780 正在播放页显示停止按钮与 0:48 / 0:48 暂停状态；重复歌曲对话框显示 `2 duplicate groups · 5 files`；720×1280 对话框不裁切。
- 两端仍无网络权限；重复检测只读取已扫描/MediaStore 元数据，不新增存储权限。
- 结果见 `base/app/checks/round-7-d/`。

未纳入本轮：真正的交叉淡化、批量重命名/移动/删除、按标签重命名、CUE 分轨、音频裁剪、格式转换、跨平台备份导入。

### 第 7 轮：音频与曲库工具

交付：

- 交叉淡化、A-B 循环（已完成）、单曲播放（已完成）。
- 本地备份/恢复（已在 Round 7-B 完成）。
- 重复检测（已完成）、失效文件定位（已完成）、批量重命名。
- 音频裁剪、CUE、格式转换（按需求裁剪范围）。

验收：

- 交叉淡化不爆音、不丢拍。
- 单曲停止不进入下一首（Round 7-D 已验证）。
- 失效文件可重新指向新路径（Round 7-C 已验证）。
- 备份恢复后播放列表、队列、收藏、设置一致（Round 7-B 已验证）。

### 第 7-D 轮后修复：桌面/悬浮歌词精简与快捷入口（已完成）

交付：

- PC 桌面歌词保留酷狗式左右双行和长歌词自动缩放；解锁态改为半透明深色卡片（`LWA_ALPHA` 215/255），锁定态使用黑色 color key 全透明，只保留描边歌词和小锁。
- PC 桌面歌词工具栏从 32 px 放大到 44 px（窄窗口 38 px），图标随之增大。
- PC 设置页字号滑杆增加 A− / A+；正在播放页新增“词”快捷按钮，只控制桌面歌词显隐，字号/透明度/锁定仍在设置中。
- Android 悬浮歌词改为紧凑单行：只显示当前歌词，不显示歌名、上一行/下一行、进度条和播放控制；长句自动缩小以保持一行完整。
- Android 解锁态为小型半透明卡片（拖动条、锁定、关闭）；锁定态移除卡片和按钮，只保留描边歌词并整窗穿透，在设置页解锁。
- Android 设置页悬浮歌词字号增加“缩小 / 放大”按钮；正在播放页新增“词”按钮，只控制悬浮歌词显隐，字号、锁定和强调色仍在设置中。
- PC 46 个单元测试；Android 47 个单元测试 + 7 个 Room 迁移测试。

验收：

- PC 解锁态可见半透明卡片，锁定态无卡片；`LWA_ALPHA` 与 `LWA_COLORKEY` 命中测试分别为 `HTCLIENT` / `HTTRANSPARENT`。
- Android 解锁态为单行歌词的小卡片；锁定后在白色 Chrome 页面上只看到描边歌词，没有黑色卡片。
- Android 设置页可看到并点击“缩小 / 放大”；播放页“词”按钮可即时隐藏/显示悬浮歌词。
- 证据见 `base/app/checks/desktop-lyrics-kugou/` 与 `base/app/checks/android-floating-lyrics-compact/`。

未纳入本轮：歌词颜色/双语行自定义、Android 锁屏歌词、快速设置磁贴。

### 第 7-D 轮后修复：底部播放条对齐（已完成）

交付：

- PC 底部播放条改为固定三栏：左右两栏等宽，中心栏 260 px；左右栏用 `set_width` 固定宽度，歌名长度不再推动中间按钮。
- 播放控制行改为固定 44 px 高并垂直居中；上一首/下一首与播放按钮的垂直中心一致。
- 控制组宽度由按钮实际尺寸和间距计算，播放按钮位于窗口中心。
- PC 46 个单元测试。

验收：

- 短标题与超长标题下，播放按钮中心均为 `x=597.5`（截图窗口宽 1196，中心 598）；上一首、下一首、随机、循环图标中心均为 `y=749.5`。
- 证据见 `base/app/checks/player-bar-alignment/`。

未纳入本轮：其他页面布局调整。

### 第 8-A 轮：PC 在线歌词（已完成）

交付：

- 首次播放无歌词时后台查询 LRCLIB；高置信匹配写 `.lrc` 与 `SongExtra`，相近版本进入候选选择，纯音乐/未找到/错误写入缓存。
- 新设置项：在线歌词总开关、AI 供应商（DeepSeek/GLM/MiMo/OpenAI/OpenCode Go/Zen/Kimi/Qwen/SiliconFlow/自定义）、接口地址、模型、获取模型、测试连接、清理缓存；API Key 使用 Windows DPAPI 加密，不进入状态文件与备份。
- 正在播放页显示来源（LRCLIB/AI/本地/已编辑）与获取状态；AI 只能手动触发，结果先预览并校验时间轴后保存；编辑或清空歌词后不会自动回填。
- LRCLIB 请求带 User-Agent、350 ms 间隔、`Retry-After` 处理和有界响应；地址自动补 `/v1`，远程 HTTP 拒绝，OpenCode Go 发送会话头。

验收：

- PC 62 项测试（61 通过，1 个 live LRCLIB 测试默认忽略）；`cargo test live_lrclib_smoke -- --ignored` 通过。
- 实测 `晴天/周杰伦` 自动写入 `.lrc`；285 秒版本进入 18 个候选；本地 OpenAI 兼容 stub 的 AI 预览/保存通过。
- 证据见 `base/app/checks/round-8-a-online-lyrics-pc/`。

未纳入本轮：在线封面、逐字歌词、AI 自动触发、批量预取全库。

### 第 8-B 轮：Android 在线歌词（已完成）

交付：

- Manifest 增加 `INTERNET` / `ACCESS_NETWORK_STATE`；回环 HTTP 仅用于本机 AI 服务。
- Room v9：`lyrics.source`、`lyrics.fetchedAt`、`lyrics_fetch_state`（状态、重试时间、候选 JSON、错误、用户禁用自动获取）。
- 播放服务在曲目切换时自动查询 LRCLIB；高置信匹配写 Room，相近版本缓存候选，播放页可选择；LRCLIB 找不到时显示手动 AI 按钮，AI 结果在编辑预览中确认后保存。
- 设置页：在线歌词开关、供应商/地址/模型、获取模型、API Key（Keystore 加密）、测试连接、清理缓存。
- 编辑/清空歌词会把来源标为 `user` 或记录 `autoDisabled`；丢失文件重连同步迁移歌词与获取状态；备份包含歌词来源/时间但不包含 Key。

验收：

- Android 56 项单元测试通过；Room 1→9 迁移测试 8 项通过。
- Android 15 模拟器实测自动 LRCLIB、AI 预览/保存、候选选择；数据库来源分别为 `lrclib`、`ai`、`lrclib`。
- APK 权限仅新增 `INTERNET` 与 `ACCESS_NETWORK_STATE`，无广告/统计/崩溃上报 SDK。
- 证据见 `base/app/checks/round-8-b-online-lyrics-android/`。

未纳入本轮：AI 自动触发、后台全库预取、跨平台歌词同步、在线封面。

### 第 8-B 轮后修复：正在播放页控制行对齐（已完成）

交付：

- PC 正在播放页的控制行改为固定 54 px 高、`left_to_right(Align::Center)`，随机、上一首、播放、下一首、循环共用同一条垂直中心线。
- 控制组宽度改为按五个按钮的实际尺寸和间距计算，不再硬编码 268 px；播放按钮与进度条中心对齐。
- 修复前上一首比下一首高 8 px、播放按钮偏左约 17 px；修复后五个控件中心均为 `y=369.5`，播放按钮距进度条中心 0.5 px。
- PC 61 项单元测试通过；PE subsystem 2。

验收：

- 同一测试状态与进度下，用 PrintWindow 对比修复前后截图。
- 证据见 `base/app/checks/now-playing-transport-alignment/`。

未纳入本轮：迷你播放器与底部播放条（底部播放条已在 v0.12.6 修复）。

### 第 8-C 轮：歌词进度染色与自定义颜色（已完成）

交付：

- PC 桌面歌词：当前行按 LRC 行时间到下一非空行时间从左到右填充，已播放部分使用“已播放颜色”，未播放部分使用“未播放颜色”；长间奏封顶 8 秒。下一行使用未播放颜色并降低透明度。
- PC 设置 → 桌面集成新增两个颜色选择器和实时双色预览；“已播放颜色”默认跟随主题强调色，“未播放颜色”默认暖白色，均可一键恢复默认。
- Android 悬浮歌词：同一套进度染色逻辑，播放时以 50 ms 刷新（暂停时 250 ms）；设置 → 悬浮歌词新增两个颜色选择器和预览，颜色对话框使用色相/饱和度/亮度滑杆。
- 原生颜色、进度百分比和备份字段：PC 设置写入 `desktop_lyrics_played_color` / `desktop_lyrics_unplayed_color`；Android 写入 `floating_played_color` / `floating_unplayed_color`，并随备份保存/恢复；旧状态和旧备份缺少字段时自动使用默认值。
- PC 64 项单元测试通过（1 项忽略）；Android 57 项单元测试通过。

验收：

- PC 固定 LRC 测试行在 75% 进度截图：左侧为强调色，右侧为暖白色，下一行为弱化色；自定义青色/暖白颜色截图同样通过。
- Android 悬浮歌词在模拟器上验证进度填充、锁定态无卡片和自定义颜色（见证据目录）。
- 证据见 `base/app/checks/lyrics-progress-colors/`。

未纳入本轮：逐字 LRC（`<mm:ss.xx>`）精确染色、双语歌词分别染色、Android 应用内歌词列表的逐行填充。

### 第 8-C 轮后修复：桌面歌词解锁按钮放大（已完成）

交付：

- PC 锁定态桌面歌词的解锁控件从 30×30 px 小方块改为 96×38 px 胶囊按钮。
- 按钮显示 18 px 解锁图标与“Unlock / 解锁”文字；悬停时背景和主题边框变亮，鼠标变为手型。
- 锁定态歌词顶部预留 38 px，放大后的按钮不会压住第一行歌词。
- PC 64 项单元测试通过（1 项忽略）；PE subsystem 2。

验收：

- 对比旧 30×30 小点与新 96×38 胶囊按钮截图。
- 证据见 `base/app/checks/desktop-lyrics-unlock-button/`。

未纳入本轮：Android 悬浮歌词的锁定/解锁入口（仍在设置页）。

### 第 8-D 轮：文件名标签兜底、AI 歌词提示词与日志（已完成）

交付：

- PC 与 Android 在标题/歌手标签缺失、为空占位符或标题等于文件名时，从 `歌手 - 歌名` 文件名补全显示标签；支持空格/无空格横线、`–`/`—`/`－`、`01.`/`(01)` 音轨前缀。真实标签和用户标签覆盖始终优先，不写回音频文件。
- 音轨前缀只在明确像音轨号时去除；`7 Rings`、`1984` 等以数字开头的标题不会被误拆。
- PC `library.rs` 不再把“祖父目录名”当作歌手；解析失败时显示 `Unknown artist`。
- Android 新增 `library/FilenameTags.kt`，MediaStore 的 `<unknown>` 和“标题等于文件名”同样视为占位符。
- 实测 `D:\音乐` 的 828 个音频文件中，307 个没有 Title、305 个没有 Artist、298 个两者都没有；其中 296 个文件名符合 `歌手 - 歌名`。单目录验证 `Aimer-After Rain`、`All Time Low - Time-Bomb`、`01. 周杰伦 - 晴天`、`(02) 周杰伦-晴天`、`7 Rings` 等均正确显示。
- AI 歌词提示词改为“根据自身知识生成草稿 LRC，时间轴可近似，保存前由用户预览编辑”，去掉“必须联网验证”的硬要求；只有完全不知道歌曲时才返回 `NOT_FOUND`。请求加入 `temperature: 0`，并识别短版权拒绝，避免把拒绝文案当成歌词。
- PC 开发者日志新增 AI 请求/结果/错误、模型列表和连接测试记录；记录 provider、model、endpoint、耗时、HTTP 状态和字符/行数，绝不记录 API Key。`developer.log` 改为追加写入，重新开启开发者模式不再清空旧日志。
- PC/Android“测试连接”现在除模型列表外还会真正发送一次最小 `chat/completions`，避免“模型列表正常但生成失败”仍显示连接成功。
- PC LRCLIB 解析容错：单个字段为 `null`（例如 `duration`）不再导致整批搜索结果失败。
- PC 67 项单元测试通过（1 项忽略）；Android 60 项单元测试通过。

验收：

- PC 单目录截图显示 `After Rain / Aimer`、`Time-Bomb / All Time Low`、`晴天 / 周杰伦`、`7 Rings / Unknown artist`。
- 使用真实 OpenCode Go 端点验证：`/models` 返回 37 个模型；同一 `ureq` 客户端请求 `晴天 / 周杰伦` 返回 33 行 LRC。`After Rain / Aimer` 仍可能因模型知识不足返回 `NOT_FOUND`，但用修正后的元数据查询 LRCLIB 返回 20 条记录，含与 4:53 文件匹配的 293 秒同步版本，因此正常路径不依赖 AI。
- 证据见 `base/app/checks/filename-tag-fallback/` 与 `base/app/checks/ai-lyrics-prompt-fix/`。

未纳入本轮：AI 联网搜索工具、保证所有冷门歌曲都能生成歌词、写回音频标签。

### 第 8-D 轮后修复：桌面歌词等比例缩放（已完成）

交付：

- PC 解锁态桌面歌词以 900×190 为设计基准；窗口尺寸变化时，当前行、下一行、行间距、工具栏按钮与图标按同一比例缩放。设置中的字号滑杆作为 900×190 基准字号，长歌词仍由 `fit_font_size` 缩到完整显示。
- 解锁时在歌词窗口滚动滚轮，可按同一比例同时缩放窗口宽高；取消原来的 460×160 最小尺寸和 3600×760 最大尺寸，只有 1×1 的原生窗口技术下限；左上角位置保持不变。
- 手动拖拽窗口也不再受 460×160 限制，`WM_GETMINMAXINFO` 的最小跟踪尺寸改为 1×1。
- 锁定态不响应缩放，并保持锁定前的显示大小；没有新增设置项、设置字段或数据迁移，窗口尺寸仍在当前会话内记忆。
- 设置页说明更新为“歌词和按钮会一起缩放，不设固定最小或最大尺寸”。

验收：

- 900×190、1350×285（1.5×）、460×160 和 200×60 截图确认歌词、按钮、图标与圆角同步缩放且不裁剪。
- 原生 `WM_MOUSEWHEEL` delta=+120 连续三次：窗口 900×190 → 954×201 → 1012×212 → 1073×224；向下滚轮恢复到 897×188；大窗口可继续超过原来的 3600×760。
- PC 70 项单元测试通过（1 项忽略）；PE subsystem 2。
- 证据见 `base/app/checks/desktop-lyrics-proportional-zoom/`。

未纳入本轮：桌面歌词窗口尺寸跨重启持久化、按宽度单独拖动时改变歌词高度。

### 第 8-D 轮后修复：桌面歌词缩放边框与光标（已完成）

交付：

- 无边框歌词窗口此前整个客户区都返回 `HTCLIENT`，Windows 不显示缩放光标；圆角窗口区域又把卡片外 6/5 px 的透明边裁掉，直接按窗口外沿计算命中区只剩约 2 px。
- `desktop.rs` 在解锁态自行处理 `WM_NCHITTEST`：从可见圆角边缘向内 8 px（左右为 6+8、上下为 5+8，按 DPI 和当前歌词缩放比例缩放）返回 `HTLEFT`/`HTRIGHT`/`HTTOP`/`HTBOTTOM` 与四角代码；锁定态仍返回 `HTTRANSPARENT`。
- 使用标准 HT 代码后，Windows 自动显示 `IDC_SIZEWE`、`IDC_SIZENS`、`IDC_SIZENWSE`，并执行原生缩放循环；不额外绘制光标。
- 圆角区域与命中测试共享边缘常量，新增纯函数 `resize_hit_code` 回归测试。

验收：

- 900×190 原生探测：左/右 `SIZEWE`，上/下 `SIZENS`，左上/右下 `SIZENWSE`，中心仍为箭头并落在歌词窗口。
- 真实鼠标拖动右下边框：900×190 → 960×230，说明原生缩放循环生效。
- PC 70 项单元测试通过（1 项忽略）；PE subsystem 2。
- 证据见 `base/app/checks/desktop-lyrics-resize-border/`。

未纳入本轮：宽度单独拖动时改变字号、桌面歌词窗口尺寸跨重启持久化。

### 第 8-D 轮后修复：歌词优先的正在播放页与 Android 全屏歌词（已完成）

交付：

- PC 正在播放页改为图 4 式两栏布局：左侧封面与歌曲信息，右侧大歌词面板，底部控制区；歌词面板占右侧全部可用高度，1180×780 时可见约 10 行，较旧的 180 px 底部面板明显增大。歌词区与控制区都不使用卡片背景或边框，直接落在封面配色页面上；底部控制区顶部是一条通栏进度线，作为歌词区与控制区的分隔。
- PC 歌词水平居中；按播放进度高亮当前行并加主题色短下划线，在当前行变化时用 `scroll_to_rect(Align::Center)` 自动滚到歌词面板中间。已播放行使用设置中的已播放颜色，未播放行使用未播放颜色；点击带时间歌词行跳转。没有歌词时在面板内显示获取状态、AI/重试/候选按钮。
- PC 右侧新增“歌词 / 详情”标签；详情保留接下来播放与文件信息。通栏进度线下方的时间分列左右，五键播放控制始终居中；A-B、单曲停止、均衡器、睡眠定时、桌面歌词、标签、速度等快捷按钮分布在两侧外缘，宽窗口用完整文字、中等窗口用短标签、最窄窗口用图标，播放键位置不变。最大化时内容宽度扩展到 2200 px，左侧封面分组垂直平衡，歌词与控制区随窗口展开。
- PC 播放页的“队列”按钮改为在当前播放页右侧打开播放队列抽屉，播放页保持打开；按 X 或 Esc 关闭。列表页使用同一队列抽屉，不再出现从播放页跳回歌曲列表、需要再次进入播放页的问题。
- Android 播放页把歌词从底部卡片移到封面与歌曲标题之间：居中显示当前行与下一行，点击整块歌词进入全屏歌词页。全屏页提供居中自动滚动、当前行高亮与背景、点击行跳转、来源标签、偏移控件、进度条和播放控制；右上角铅笔进入原歌词编辑页，菜单同时保留“歌词/编辑歌词”入口。
- PC 70 项单元测试通过（1 项忽略）；Android 60 项单元测试通过；Android 模拟器 1080×1920 实测播放页与全屏歌词页。
- 证据见 `base/app/checks/now-playing-lyrics-layout/` 和 `base/app/checks/android-player-lyrics-redesign/`。

未纳入本轮：逐字歌词、双语翻译行、Android 锁屏歌词。

### 第 9 轮：Android 系统集成

交付：

- 快捷设置磁贴。
- Android Auto。
- 车载/驾驶模式。
- 锁屏歌词（大号通知或自定义锁屏页）。

验收：

- 磁贴与 Auto 真实控制播放；小部件已在 Round 7-A 验证。
- Android Auto 可浏览并播放本地曲库。
- 断网时本地播放可用；在线歌词请求失败不得阻塞播放。

---

## 8. 统一验收标准

每轮至少完成：

1. 构建通过：PC `cargo build --release`；Android `gradlew.bat assembleDebug`。
2. 单元测试通过：M3U 往返、筛选、排序、歌词解析、播放计数、备份恢复。
3. 运行验证：冷启动 → 扫描 → 播放 → 切歌 → 队列 → 通知栏/播放条。
4. 布局检查：720×1280、1080×1920 无重叠、文字不截断、播放条不遮挡列表。
5. 断网时本地播放可用；Android 仅申请歌词获取所需的 `INTERNET` / `ACCESS_NETWORK_STATE`。
6. 截图和说明写入 `base/app/checks/round-N/`。
7. 设置修改重启后保留。
8. 不复制目标 APK 的代码、图片、字体、音频、名称或文案。

新增功能补充验收：

- 收藏/统计：重启后数据一致。
- 歌词：同步位置误差可接受，无歌词时有明确空状态。
- 在线歌词：LRCLIB 请求带标识与限速；模糊版本不自动覆盖；AI 结果必须预览；
  只发送歌名/艺术家/专辑/时长，API Key 加密且不进入备份；关闭开关后不发起请求。
- 标签：保存后列表、播放页、通知栏同步更新。
- 小部件/Auto：控制真实生效，不是模拟界面。
- 备份：同版本往返恢复一致。

---

## 9. 待确认问题

1. Android 标签编辑先做“虚拟覆盖”还是直接写回文件？（已选择虚拟覆盖，Room v6；文件写回未做）
2. 歌词是否只支持 LRC/内嵌，还是也要支持 VTT/KRC？
3. PC 全局热键的默认组合是否采用本文建议？（已采用，Round 6-D1；可在设置关闭）
4. Android 小部件、磁贴、Android Auto 的优先级是否高于音频工具？
5. APE/DSD/CUE 是否有真实使用需求，值得投入高成本？
6. 备份文件是否需要跨 PC/Android 共用格式？（当前实现：各平台独立格式、平台内恢复；跨平台未做）
7. 收藏是否只针对歌曲，还是也要收藏专辑/艺术家？（当前实现：仅歌曲）
8. 播放计数阈值是否沿用 PC 的默认 30%？（当前实现：30%）

---

## 10. 实施进度

| 日期 | 批次 | 状态 | 内容 |
|---|---|---|---|
| 2026-11-22 | Round 4-A | 完成 | Room v2、`song_state` 用户数据表、迁移测试、收藏、播放计数、Songs/Favorites 页面 |
| 2026-11-22 | Round 4-B | 完成 | Albums/Artist 分组、列表、详情页、播放全部/随机、收藏/队列/播放列表入口 |
| 2026-11-22 | Round 4-C | 完成 | Recently added、Most played、History、Bookmarks、Room v3、搜索分组/历史/高亮、排序、多选、A–Z 索引 |
| 2026-11-22 | Round 5-A | 完成 | Room v4 歌词表、LRC 解析与高亮、歌词编辑页、本地 `.lrc`/`.txt` 导入 |
| 2026-11-22 | Round 5-B | 完成 | 服务内睡眠定时（时分/曲目数/当前曲结束）、播放/暂停淡入淡出、设置项 |
| 2026-11-22 | Round 5-C | 完成 | 22 预设、前级/余量建议、低音、虚拟环绕、平衡、按设备配置、Room v5 前的基础 |
| 2026-11-22 | Round 5-D | 完成 | Room v5 队列位置字段、续播、5 秒/暂停保存、开关 |
| 2026-11-22 | Round 6-A | 完成 | PC/Android 当前歌曲定位、Android 播放列表/队列上下移动、队列详情页、21 个单元测试 |
| 2026-11-23 | Round 6-B | 完成 | Room v6 虚拟标签覆盖、单曲标签编辑、统一读取合并、当前播放 metadata 刷新、24 个单元测试 |
| 2026-11-23 | Round 6-C | 完成 | 歌词每首偏移、字号/透明度、PC/Android 控件与预览、Room v7、25 个单元测试、6 个迁移测试 |
| 2026-11-23 | Round 6-D1 | 完成 | PC 托盘、关闭/最小化到托盘、6 组全局热键、隐藏播放心跳、设置卡片、21 个单元测试 |
| 2026-11-23 | Round 6-D2 | 完成 | PC 桌面歌词、锁定/穿透、迷你播放器、Windows SMTC、托盘/设置入口、23 个单元测试 |
| 2026-11-23 | Round 7-A | 完成 | Android 悬浮歌词、锁定/穿透、18–44 sp 字号、RemoteViews 主屏小部件、固定入口、30 个单元测试 |
| 2026-11-23 | Round 7-B | 完成 | PC/Android 本地 JSON 备份与恢复、版本校验、引用回退、设置页入口、PC 29 项 + Android 37 项单元测试 |
| 2026-11-23 | Round 7-C | 完成 | PC/Android A-B 循环、失效文件重连、Room v8 `missing_songs`、PC 32 项 + Android 41 项 + 7 项迁移测试 |
| 2026-11-23 | Round 7-D | 完成 | PC/Android 单曲停止、重复歌曲检测、PC 37 项 + Android 47 项 + 7 项迁移测试 |
| 2026-11-23 | 桌面歌词改造 | 完成 | PC 解锁态半透明、锁定态全透明、浮钮解锁、收藏/上一首/下一首/播放控制、PC 43 项测试 |
| 2026-11-23 | 桌面歌词酷狗式改版 | 完成 | PC 左右错位双行、长歌词自动缩放完整显示、文字描边、PC 46 项测试 |
| 2026-11-23 | 桌面/悬浮歌词精简 | 完成 | PC 解锁半透明卡片/锁定全透明、字号 A−/A+、播放页“词”；Android 单行无卡片锁定态、字号缩放、“词”开关；PC 46 + Android 47 + 7 项迁移测试 |
| 2026-11-23 | 桌面歌词按钮放大 | 完成 | PC 工具栏 32 → 44 px（窄窗口 38 px），图标同步放大，PC 46 项测试 |
| 2026-11-24 | 底部播放条对齐 | 完成 | 固定三栏布局、上一首/下一首与播放按钮垂直居中、长标题不再推动中心按钮、PC 46 项测试 |
| 2026-11-24 | 正在播放页控制行对齐 | 完成 | 固定 54 px 控制行、五键同轴、播放按钮与进度条居中、PC 61 项测试 |
| 2026-11-24 | 歌词进度染色 | 完成 | PC 桌面歌词与 Android 悬浮歌词按 LRC 进度双色填充、自定义颜色与预览、PC 64 项 + Android 57 项测试 |
| 2026-11-24 | 桌面歌词解锁按钮 | 完成 | 30×30 小点改为 96×38 胶囊按钮、图标 + Unlock/解锁文字、悬停高亮、PC 64 项测试 |
| 2026-11-24 | 文件名标签与 AI 歌词修复 | 完成 | PC/Android 扫描按 `歌手 - 歌名` 兜底、AI 草稿提示词与拒绝识别、PC AI 开发者日志、LRCLIB null 容错、PC 67 项 + Android 60 项测试 |
| 2026-11-24 | 桌面歌词无级缩放 | 完成 | 解锁态歌词、行距、工具栏按钮与图标同步缩放；取消 460×160 / 3600×760 限制、PC 70 项测试 |
| 2026-11-24 | 桌面歌词缩放边框 | 完成 | 解锁态 8 px 可见缩放边框、标准双向/斜向光标、原生拖动缩放、PC 70 项测试 |
| 2026-11-24 | 歌词优先播放页 | 完成 | PC 两栏歌词页、居中自动滚动、无卡片背景、通栏进度线分隔、两侧快捷按钮与居中五键控制、最大化自适应；Android 居中当前/下一行与全屏歌词；PC 70 项 + Android 60 项测试 |
| 2026-11-25 | Android 导航栏避让 | 完成 | 主页迷你播放器与播放页底部控制使用 `WindowInsets.navigationBars`，手势导航与三键导航均不重叠；Android 60 项测试 |
| 2026-11-25 | PC 播放页队列抽屉 | 完成 | 播放页队列按钮改为当前页右侧抽屉，播放页保持打开；列表页与播放页共用队列抽屉 |
| 2026-11-23 | Bugfix | 完成 | PC M4A/MP4 解码崩溃修复、内容探测标签/时长、损坏文件错误提示、PC 41 项测试 |
| 下一步 | Round 7-E | 待定 | 真正的交叉淡化、批量重命名、Android 磁贴/Auto |

Round 4 的截图、数据库迁移和计数验证见 `base/app/checks/round-4/`；Round 4-C 的验证见 `base/app/checks/round-4-c/`；Round 5 的歌词、睡眠/淡入淡出、均衡器和队列位置验证见 `base/app/checks/round-5/`；Round 6-A 的当前歌曲定位与排序验证见 `base/app/checks/round-6/`；Round 6-B 的单曲虚拟标签编辑见 `base/app/checks/round-6-b/`；Round 6-C 的歌词偏移与显示设置见 `base/app/checks/round-6-c/`；Round 6-D1 的托盘与全局热键验证见 `base/app/checks/round-6-d1/`；Round 6-D2 的桌面歌词、迷你播放器与 SMTC 验证见 `base/app/checks/round-6-d2/`；Round 7-A 的悬浮歌词与主屏小部件验证见 `base/app/checks/round-7-a/`；Round 7-B 的备份导出、恢复确认与恢复后数据验证见 `base/app/checks/round-7-b/`；Round 7-C 的 A-B 循环与失效文件重连验证见 `base/app/checks/round-7-c/`；Round 7-D 的单曲停止与重复歌曲检测验证见 `base/app/checks/round-7-d/`；M4A/MP4 崩溃修复验证见 `base/app/checks/bugfix-m4a-crash/`；桌面歌词透明化与浮钮解锁验证见 `base/app/checks/desktop-lyrics-redesign/`；酷狗式左右双行与自动缩放验证见 `base/app/checks/desktop-lyrics-kugou/`；Android 单行悬浮歌词、无卡片锁定态与“词”开关验证见 `base/app/checks/android-floating-lyrics-compact/`；文件名标签兜底验证见 `base/app/checks/filename-tag-fallback/`；AI 草稿提示词与日志验证见 `base/app/checks/ai-lyrics-prompt-fix/`；桌面歌词等比例缩放验证见 `base/app/checks/desktop-lyrics-proportional-zoom/`；桌面歌词缩放边框与光标验证见 `base/app/checks/desktop-lyrics-resize-border/`；歌词优先的 PC 播放页验证见 `base/app/checks/now-playing-lyrics-layout/`；Android 居中歌词与全屏歌词页验证见 `base/app/checks/android-player-lyrics-redesign/`；Android 手势/三键导航栏避让验证见 `base/app/checks/android-navigation-insets/`。

---

## 11. 依据文件

- `base/app/README.md`：PC 功能说明
- `base/app/android/README.md`：Android 功能说明
- `base/app/src/model.rs`：PC 设置、数据模型、页面枚举
- `base/app/src/ui/pages.rs`：PC 设置页与列表页面
- `base/app/src/ui/common.rs`：PC 歌曲表格、排序、多选、收藏、书签
- `base/app/src/library.rs`：扫描、文件名标签兜底、标签、歌词、偏移换算、M3U、CSV
- `base/app/src/console.rs`：开发者日志追加写入与开发者控制台
- `base/app/src/ui/dialogs.rs`：PC 正在播放浮层、歌词面板、A-B/单曲停止控件、失效文件与重复歌曲对话框
- `base/app/src/ui/now_playing.rs`：PC 歌词优先的两栏正在播放页、自动滚动歌词、详情标签与底部播放条
- `base/app/src/ui/shell.rs`：PC 主框架、列表页队列抽屉与播放页共用的队列内容
- `base/app/src/app.rs`：PC 播放心跳、A-B 循环、单曲停止、失效文件检测与重连
- `base/app/src/duplicates.rs`：PC 重复歌曲分组、副本排序与大小格式
- `base/app/src/backup.rs`：PC 版本化备份、校验、ID 归一化与恢复
- `base/app/src/lyrics_fetch.rs`：PC LRCLIB 匹配、缓存、OpenAI 兼容 AI、限速与候选
- `base/app/src/secret.rs`：PC Windows DPAPI API Key 加密
- `base/app/android/app/src/main/java/dev/lilt/player/library/OnlineLyrics.kt`：Android 歌词匹配、AI 清理、供应商预设与 URL 规范化
- `base/app/android/app/src/main/java/dev/lilt/player/data/OnlineLyricsClient.kt`：Android LRCLIB/AI HTTP 客户端
- `base/app/android/app/src/main/java/dev/lilt/player/data/OnlineLyricsEngine.kt`：Android 自动获取与 Room 写入
- `base/app/android/app/src/main/java/dev/lilt/player/library/FilenameTags.kt`：Android 文件名标签解析与 MediaStore 占位符处理
- `base/app/android/app/src/main/java/dev/lilt/player/data/SecretStore.kt`：Android Keystore API Key 加密
- `docs/online-lyrics.md`：在线歌词来源、隐私、限速、供应商与 Key 说明
- `base/app/checks/round-8-a-online-lyrics-pc/README.md`：PC 自动 LRCLIB、候选、AI 预览与设置验证
- `base/app/checks/round-8-b-online-lyrics-android/README.md`：Android 自动 LRCLIB、候选、AI 预览、Room v9 与权限验证
- `base/app/android/app/src/main/java/dev/lilt/player/data/Backup.kt`：Android 备份 JSON、平台校验与曲目引用解析
- `base/app/android/app/src/main/java/dev/lilt/player/data/BackupManager.kt`：Android Room 备份收集与事务恢复
- `base/app/checks/round-7-b/README.md`：PC/Android 备份恢复验证
- `base/app/checks/round-7-c/README.md`：PC/Android A-B 循环与失效文件重连验证
- `base/app/checks/round-7-d/README.md`：PC/Android 单曲停止与重复歌曲检测验证
- `base/app/android/app/src/main/java/dev/lilt/player/data/MissingFiles.kt`：Android 失效引用聚合
- `base/app/android/app/src/main/java/dev/lilt/player/ui/MissingFilesScreen.kt`：Android 失效文件列表与替代歌曲选择
- `base/app/android/app/src/main/java/dev/lilt/player/library/PlaybackTools.kt`：Android A-B 循环与单曲停止判定
- `base/app/android/app/src/main/java/dev/lilt/player/library/DuplicateTools.kt`：Android 重复歌曲分组与大小格式
- `base/app/android/app/src/main/java/dev/lilt/player/ui/DuplicatesScreen.kt`：Android 重复歌曲列表与试听
- `base/app/android/app/src/main/java/dev/lilt/player/data/Models.kt`：Android 数据模型
- `base/app/android/app/src/main/java/dev/lilt/player/data/SettingsStore.kt`：Android 设置
- `base/app/android/app/src/main/java/dev/lilt/player/library/Sorting.kt`：Android 排序与字母索引
- `base/app/android/app/src/main/java/dev/lilt/player/library/Search.kt`：Android 分组搜索
- `base/app/android/app/src/main/java/dev/lilt/player/ui/LibraryScreens.kt`：Android 曲库、历史、书签与多选
- `base/app/android/app/src/main/java/dev/lilt/player/ui/SettingsScreen.kt`：Android 设置页
- `base/app/android/app/src/main/java/dev/lilt/player/ui/PlayerScreen.kt`：Android 播放页、居中当前/下一行歌词与睡眠对话
- `base/app/android/app/src/main/java/dev/lilt/player/ui/Components.kt`：Android 公共组件，含避让系统导航栏的迷你播放器
- `base/app/android/app/src/main/java/dev/lilt/player/ui/LyricsPlayerScreen.kt`：Android 全屏歌词、自动居中滚动、偏移与播放控制框
- `base/app/android/app/src/main/java/dev/lilt/player/ui/LyricsScreen.kt`：Android 歌词编辑页
- `base/app/android/app/src/main/java/dev/lilt/player/library/Lrc.kt`：LRC 解析、偏移高亮、时间格式
- `base/app/android/app/src/main/java/dev/lilt/player/library/FloatingLyrics.kt`：悬浮歌词行窗口、字号夹取与时间格式
- `base/app/android/app/src/main/java/dev/lilt/player/playback/FloatingLyricsOverlay.kt`：Android 悬浮歌词窗口、拖动、锁定与穿透
- `base/app/android/app/src/main/java/dev/lilt/player/widget/LiltWidgetProvider.kt`：RemoteViews 小部件与入口
- `base/app/android/app/src/main/java/dev/lilt/player/widget/WidgetRenderer.kt`：小部件渲染、封面缓存与每秒状态
- `base/app/android/app/src/main/java/dev/lilt/player/widget/WidgetActionReceiver.kt`：小部件播放控制
- `base/app/android/app/src/main/java/dev/lilt/player/ui/Components.kt`：Android 共用偏移控件
- `base/app/android/app/src/main/java/dev/lilt/player/library/Eq.kt`：均衡器配置、预设、前级与平衡计算
- `base/app/android/app/src/main/java/dev/lilt/player/library/QueueMemory.kt`：队列续播位置计算
- `base/app/android/app/src/main/java/dev/lilt/player/library/Reordering.kt`：列表上下移动算法
- `base/app/android/app/src/main/java/dev/lilt/player/library/TagEditing.kt`：虚拟标签合并与数字解析
- `base/app/android/app/src/main/java/dev/lilt/player/ui/QueueDetailScreen.kt`：队列详情、播放与排序
- `base/app/android/app/src/main/java/dev/lilt/player/ui/TagEditorScreen.kt`：单曲标签编辑
- `base/app/android/app/src/main/java/dev/lilt/player/playback/PlaybackService.kt`：媒体服务、睡眠定时、淡入淡出、音效、悬浮歌词与小部件状态推送
- `base/app/checks/round-4/README.md`：收藏、专辑/艺术家、Room v2 验证
- `base/app/checks/round-4-c/README.md`：列表、搜索、历史、书签与 Room v3 验证
- `base/app/checks/round-5/README.md`：歌词、睡眠/淡入淡出、均衡器与 Room v5 验证
- `base/app/checks/round-6/README.md`：当前歌曲定位、播放列表/队列排序验证
- `base/app/checks/round-6-b/README.md`：单曲虚拟标签编辑与 Room v6 验证
- `base/app/checks/round-6-c/README.md`：歌词偏移、字号/透明度与 Room v7 验证
- `base/app/checks/round-6-d1/README.md`：托盘、关闭到托盘与全局热键验证
- `base/app/checks/round-6-d2/README.md`：桌面歌词、锁定穿透、迷你播放器与 SMTC 验证
- `base/app/checks/desktop-lyrics-redesign/README.md`：桌面歌词透明化、锁定态、浮钮解锁与工具栏验证
- `base/app/checks/desktop-lyrics-kugou/README.md`：酷狗式左右双行、半透明解锁卡片、全透明锁定态、A−/A+ 与“词”快捷开关验证
- `base/app/checks/android-floating-lyrics-compact/README.md`：Android 单行悬浮歌词、无卡片锁定态、字号缩放与“词”开关验证
- `base/app/checks/player-bar-alignment/README.md`：底部播放条固定三栏、长标题不漂移与按钮垂直对齐验证
- `base/app/checks/now-playing-transport-alignment/README.md`：正在播放页控制行五键同轴与播放按钮居中验证
- `base/app/checks/lyrics-progress-colors/README.md`：PC 桌面歌词与 Android 悬浮歌词进度染色、自定义颜色与预览验证
- `base/app/checks/desktop-lyrics-unlock-button/README.md`：锁定态桌面歌词解锁按钮放大与悬停验证
- `base/app/checks/filename-tag-fallback/README.md`：PC/Android 文件名标签兜底、数字标题与音轨前缀验证
- `base/app/checks/ai-lyrics-prompt-fix/README.md`：AI 草稿提示词、真实端点诊断、拒绝识别与开发者日志验证
- `base/app/checks/desktop-lyrics-proportional-zoom/README.md`：PC 解锁态桌面歌词窗口高度/滚轮等比例缩放验证
- `base/app/checks/desktop-lyrics-resize-border/README.md`：PC 桌面歌词可见缩放边框、标准光标与原生拖动验证
- `base/app/checks/round-7-a/README.md`：Android 悬浮歌词、锁定穿透与主屏小部件验证
- `base/app/src/desktop.rs`：Windows 托盘菜单、全局热键、隐藏唤醒、心跳、分层歌词窗口、命中测试与 SMTC
- `base/app/src/ui/companion.rs`：桌面歌词、解锁浮钮与迷你播放器 deferred viewport
- `base/app/src/icon.rs`：窗口/托盘共用的自绘图标

---

## 12. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-11-22 | 初稿：PC/Android 对照、酷狗本地功能增量、路线图与待确认问题 |
| v0.2 | 2026-11-22 | 更新 Android Round 4／4-C 完成状态：曲库页面、收藏、统计、书签、搜索、排序、多选、字母索引与 Room v3 |
| v0.3 | 2026-11-22 | 更新 Android Round 5 完成状态：歌词、服务内睡眠定时、淡入淡出、均衡器增强、队列位置记忆与 Room v5 |
| v0.4 | 2026-11-22 | Round 6-A：PC/Android 当前歌曲定位、Android 播放列表/队列上下移动、队列详情页 |
| v0.5 | 2026-11-23 | Round 6-B：Android Room v6 虚拟标签覆盖、单曲标签编辑、统一读取合并与播放 metadata 刷新 |
| v0.6 | 2026-11-23 | Round 6-C：PC/Android 每首歌词偏移、字号与透明度、Room v7、偏移控件与调整后时间预览 |
| v0.7 | 2026-11-23 | Round 6-D1：PC 托盘、关闭/最小化到托盘、6 组全局热键、隐藏播放心跳与设置卡片 |
| v0.8 | 2026-11-23 | Round 6-D2：PC 桌面歌词（锁定/穿透）、迷你播放器、Windows SMTC、托盘与设置入口 |
| v0.9 | 2026-11-23 | Round 7-A：Android 悬浮歌词（拖动/锁定/穿透）、RemoteViews 主屏小部件、权限与设置入口 |
| v0.10 | 2026-11-23 | Round 7-B：PC/Android 本地 JSON 备份与恢复、版本校验、引用解析、设置与事务恢复 |
| v0.11 | 2026-11-23 | Round 7-C：PC/Android A-B 循环、失效文件重连、Room v8 `missing_songs`、设置与歌单入口 |
| v0.12 | 2026-11-23 | Round 7-D：PC/Android 单曲停止、重复歌曲检测、最佳副本标记与试听入口 |
| v0.12.1 | 2026-11-23 | Bugfix：PC 本地 Symphonia 解码器、M4A/MP4 崩溃修复、内容探测标签/时长 |
| v0.12.2 | 2026-11-23 | 桌面歌词改造：解锁态半透明、锁定态全透明、浮钮解锁、收藏/上一首/下一首/播放控制 |
| v0.12.3 | 2026-11-23 | 桌面歌词酷狗式改版：左右双行、长歌词自动缩放完整显示、文字描边 |
| v0.12.4 | 2026-11-23 | 桌面/悬浮歌词精简：PC 解锁半透明卡片、Android 单行无卡片锁定态、A−/A+ 与播放页“词”开关 |
| v0.12.5 | 2026-11-23 | 桌面歌词工具栏放大：44 px 按钮（窄窗口 38 px），图标同步放大 |
| v0.12.6 | 2026-11-24 | 底部播放条对齐：固定三栏布局、播放按钮居中、上一首/下一首垂直对齐 |
| v0.13.0 | 2026-11-24 | Round 8-A：PC 自动 LRCLIB、候选版本、缓存、手动 AI 预览、星轨链路供应商、DPAPI Key |
| v0.13.1 | 2026-11-24 | Round 8-B：Android 自动 LRCLIB、候选选择、AI 预览、Room v9、Keystore Key、INTERNET 权限 |
| v0.13.2 | 2026-11-24 | 正在播放页控制行对齐：固定 54 px 行高、上一首/下一首同轴、播放按钮与进度条居中 |
| v0.13.3 | 2026-11-24 | 歌词进度染色：PC 桌面歌词与 Android 悬浮歌词双色填充、自定义颜色与预览 |
| v0.13.4 | 2026-11-24 | 桌面歌词解锁按钮：30×30 小点改为 96×38 胶囊按钮，带图标与 Unlock/解锁文字 |
| v0.13.5 | 2026-11-24 | 文件名标签兜底、AI 草稿提示词与拒绝识别、PC AI 开发者日志、LRCLIB null 容错 |
| v0.13.6 | 2026-11-24 | 桌面歌词等比例缩放：解锁态窗口高度/滚轮同步缩放双行歌词，设置页更新说明 |
| v0.13.7 | 2026-11-24 | 桌面歌词缩放边框：可见边缘 8 px 原生命中区，显示标准双向/斜向光标并可拖动缩放 |
| v0.13.8 | 2026-11-24 | 桌面歌词无级缩放：取消最小/最大尺寸限制，歌词、按钮与图标一起缩放 |
| v0.13.9 | 2026-11-24 | 歌词优先播放页：PC 两栏布局、居中自动滚动与歌词/详情标签；Android 居中当前/下一行与全屏歌词页 |
| v0.13.10 | 2026-11-24 | 正在播放页视觉收口：去掉卡片底色、通栏进度条分隔、快捷按钮分列两侧且五键播放控制保持居中 |
| v0.13.11 | 2026-11-24 | 播放页自适应最大化：内容宽度扩展到 2200 px，左侧封面分组垂直平衡，底部五键与两侧控件完整显示 |
| v0.13.12 | 2026-11-25 | Android 系统栏避让：主页迷你播放器与播放页底部控制使用 `navigationBars` 内边距，手势/三键导航均不重叠 |
| v0.13.13 | 2026-11-25 | PC 播放页队列改为当前页右侧抽屉，播放页保持打开；列表页与播放页共用同一队列抽屉 |
