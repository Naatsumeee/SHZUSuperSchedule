$ErrorActionPreference = "Continue"
$base = "C:\Users\xutia\WorkBuddy\android-toolchain"
$env:JAVA_HOME = "$base\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$base\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$base\gradle-home"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Remove-Item Env:HTTP_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:HTTPS_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:http_proxy -ErrorAction SilentlyContinue
Remove-Item Env:https_proxy -ErrorAction SilentlyContinue

Set-Location "C:\Users\xutia\WorkBuddy\SHZUClassList\shzu_class_km"
& "$base\gradle\gradle-9.7.1\bin\gradle.bat" assembleRelease --no-daemon --console=plain 2>&1 | Tee-Object -FilePath "C:\Users\xutia\WorkBuddy\SHZUClassList\km_build_v133.txt"
