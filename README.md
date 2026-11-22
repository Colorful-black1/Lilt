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

## 桌面版：打包到 `dist/`

在**仓库根目录**执行下面的 PowerShell。它会先删除并重建 `dist/`，生成
`dist/Lilt-0.1.0-win64/` 目录和 `dist/Lilt-0.1.0-win64-portable.zip` 便携包：

```powershell
Push-Location base/app

cargo build --release

$name = "Lilt-0.1.0-win64"
$repo = (Resolve-Path "..\..").Path
$dist = Join-Path $repo "dist"
$stage = Join-Path $dist $name
$zip = Join-Path $dist "$name-portable.zip"

Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $stage -Force | Out-Null

Copy-Item "target\release\lilt.exe" $stage
Copy-Item "README.md" $stage
Copy-Item "RUN-zh.txt" $stage
Copy-Item "Starter Music" $stage -Recurse

Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -Force

Pop-Location
Get-Item $zip | Select-Object FullName, Length
```

打包内容：

```text
dist/Lilt-0.1.0-win64/
├─ lilt.exe
├─ README.md
├─ RUN-zh.txt
└─ Starter Music/...
```

`dist/` 是可重新生成的发布产物，已加入 `.gitignore`。

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

- `base/app/checks/round-1/`、`round-2/`、`round-3/`：桌面版与 Android 版各轮截图、UI dump、测试说明
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
