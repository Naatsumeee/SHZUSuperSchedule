# 石大课表 APK 构建脚本
# 用法：在配置好 JAVA_HOME/ANDROID_HOME/Flutter 后运行
$ErrorActionPreference = "Stop"

$base = "C:\Users\xutia\WorkBuddy\android-toolchain"
$env:JAVA_HOME = "$base\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$base\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$flutter = "$base\flutter\bin\flutter.bat"
$env:PATH = "$base\jdk17\jdk-17.0.20.1+1\bin;$base\flutter\bin;$env:PATH"

$proj = "C:\Users\xutia\WorkBuddy\SHZUClassList\shzu_class_app"

Write-Host "== 检查 flutter =="
& $flutter --version

Write-Host "== 创建项目 =="
if (Test-Path $proj) { Remove-Item -Recurse -Force $proj }
& $flutter create --org com.shzu --project-name shzu_class --platforms android $proj

Write-Host "== 复制源码 =="
Copy-Item -Recurse -Force "C:\Users\xutia\WorkBuddy\SHZUClassList\app_src\lib" $proj
Copy-Item -Force "C:\Users\xutia\WorkBuddy\SHZUClassList\app_src\pubspec.yaml" $proj

Write-Host "== pub get =="
Push-Location $proj
& $flutter pub get

Write-Host "== 构建 APK =="
& $flutter build apk --release

Write-Host "== 完成 =="
Pop-Location
