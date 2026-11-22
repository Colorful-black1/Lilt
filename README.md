# Lilt

Lilt 是一个纯离线的本地音乐播放器，对目标应用 Musicolet 6.14.1 的功能面做了独立复刻：
行为与操作路径对齐，但不使用目标的名称、图标、字体、截图、音频、原始文案或反编译产物。

仓库包含两个可运行版本：

- **桌面版**：Rust + egui/eframe + Rodio，源码在 `base/app`
- **Android 版**：Kotlin + Jetpack Compose + Media3 + Room，源码在 `base/app/android`

两个版本都不申请网络权限，不包含广告、统计或崩溃上报 SDK。

## 目录结构

```text
musicolet_craft/
├─ base/
│  ├─ app/                     # 桌面版工程
│  │  ├─ src/                  # Rust 源码
│  │  ├─ Starter Music/        # 10 首原创示例音乐（运行时需要）
│  │  ├─ findings/             # 目标行为研究记录
│  │  ├─ checks/               # 各轮验收截图与说明
│  │  ├─ android/              # Android 版工程
│  │  └─ README.md             # 桌面版详细说明
│  └─ checks/                  # 工作区级验收产物
├─ dist/                       # 打包输出（不入库）
├─ base.apk                    # 目标参考 APK（不入库）
├─ prompt-musicolet.md         # 原始任务提示词
├─ package.ps1                 # 一键打包脚本（PowerShell）
├─ package.sh                  # Git Bash / WSL 入口
├─ .gitignore
├─ .gitattributes
└─ .editorconfig
```

## 桌面版：构建与运行

环境要求：Rust stable（Windows 使用 MSVC toolchain）、Windows 10/11。

```powershell
cd base/app
cargo run --release
```

只构建不运行：

```powershell
cd base/app
cargo build --release
```

产物：`base/app/target/release/lilt.exe`。

截图与无焦点启动等调试参数见 `base/app/README.md`。

### 控制台与开发者模式

- release 构建和打包后的 `lilt.exe` 使用 Windows GUI 子系统，双击时不会弹出终端；关闭终端也不会影响播放。
- 需要查看诊断信息时，打开 **Settings → Developer → Developer mode**（设置 → 开发者 → 开发者模式）。开启后会打开独立的 `Lilt developer console` 窗口，实时显示日志。
- 开发者控制台窗口可以随时关闭，不会停止播放器；关闭开发者模式时窗口会自动退出。
- 日志文件：`%APPDATA%\Lilt\developer.log`（或 `LILT_DATA_DIR` 指定目录下的 `developer.log`）。
- `--list-audio`、`--help` 等命令行参数仍会输出到启动它的终端。

## 一键打包

在**仓库根目录**执行（PowerShell）：

```powershell
.\package.ps1 pc     # 桌面版：构建并打包到 dist/
.\package.ps1 apk    # Android 版：构建 debug APK
.\package.ps1 all    # 两者都做
```

如果系统禁止运行脚本，改用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\package.ps1 pc
```

Git Bash / WSL：

```bash
./package.sh pc
./package.sh apk
./package.sh all
```

### `pc`：桌面便携包

从 `base/app/Cargo.toml` 读取版本号，构建 release，然后生成：

```text
dist/Lilt-<version>-win64/
├─ lilt.exe
├─ README.md
├─ RUN-zh.txt
└─ Starter Music/...
dist/Lilt-<version>-win64-portable.zip
```

脚本只重建同名的目录和 zip，不会删除 `dist/` 中的其他文件。`dist/` 已加入 `.gitignore`。

### `apk`：Android 调试包

构建 `assembleDebug`，APK 输出到：

```text
base/app/android/app/build/outputs/apk/debug/app-debug.apk
```

同时复制一份到 `base/app/Lilt-<versionName>-debug.apk`（该文件不入库）。

## Android 版：构建 APK

环境要求：JDK 17+、Android SDK Platform 35。首次运行 Gradle Wrapper 会下载 Gradle 8.13 和依赖。

在**仓库根目录**执行：

```powershell
Push-Location base/app/android
.\gradlew.bat assembleDebug
Pop-Location
```

APK 输出位置：

```text
base/app/android/app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```powershell
Push-Location base/app/android
.\gradlew.bat testDebugUnitTest
Pop-Location
```

安装到已连接的手机或模拟器：

```powershell
adb install -r base/app/android/app/build/outputs/apk/debug/app-debug.apk
```

如需把调试包同步到 `base/app/Lilt-1.0-debug.apk`（该文件不入库）：

```powershell
Copy-Item base/app/android/app/build/outputs/apk/debug/app-debug.apk base/app/Lilt-1.0-debug.apk -Force
```

构建 release 包（默认未签名，正式分发前需自行配置签名）：

```powershell
Push-Location base/app/android
.\gradlew.bat bundleRelease
Pop-Location
```

## 验收证据

- `base/app/checks/round-1/`、`round-2/`、`round-3/`、`round-4/`、`round-4-c/`：桌面版与 Android 版各轮截图、UI dump、测试说明
- `base/app/shots/`：布局检查截图
- `base/checks/`：工作区级检查截图

## 版本库约定

以下内容不入库，可通过上面的命令重新生成：

- Rust `base/app/target/`
- Android `base/app/android/{.gradle,.kotlin,app/build}/` 与 `local.properties`
- `dist/`、`*.apk`、`*.aab`、`*.exe`、`*.pdb`、`*.zip`
- `*.log`
- `base.apk`（目标的第三方 APK，仅本地保留用于对照）
- `.idea/`、`*.iml`、`.vscode/`
- `base/investigation.cany/` 及 `craft.json`、`feed.json`、`transcript.json`、`usage.json` 等 craft 工具工作数据

`base/app/Starter Music/` 必须入库：桌面版通过 `CARGO_MANIFEST_DIR` 从此目录加载示例音乐。

## 更多说明

- 桌面版功能、快捷键与数据目录：`base/app/README.md`
- Android 版权限、功能范围与限制：`base/app/android/README.md`
- 目标行为对照与数值来源：`base/app/MECHANICS.md`、`base/app/findings/feature-map.md`
