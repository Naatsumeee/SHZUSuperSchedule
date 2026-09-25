# 运行 JVM 单元测试（与 build_km.ps1 完全相同的环境设置，保证结果可比）
# 用法：powershell -ExecutionPolicy Bypass -File tools/test_km.ps1
[CmdletBinding()]
param(
    [switch]$NoLog
)

$ErrorActionPreference = "Continue"

# ---------- 环境（与 build_km.ps1 保持一致）----------
$base = "C:\Users\xutia\WorkBuddy\android-toolchain"
# 工程根由脚本自身位置推出：多份工作副本时各自作用于自己所在的那一份。
# ⚠️ PS 5.1 下 $PSScriptRoot 在某些调用方式中为空，必须显式兜底，
#    否则 Join-Path 会因空路径直接报错。
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$repo = Split-Path -Parent $scriptDir
$proj = Join-Path $repo "shzu_class_km"

$env:JAVA_HOME = "$base\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$base\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$base\gradle-home"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

foreach ($p in "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy") {
    Remove-Item "Env:$p" -ErrorAction SilentlyContinue
}

$logPath = Join-Path $repo "km_test.txt"

Write-Host "== 运行单元测试（日志：km_test.txt）==" -ForegroundColor Cyan
Set-Location $proj

# --no-daemon 与构建脚本一致：本机内存紧张，常驻 daemon 会被 OOM 杀掉
$out = & "$base\gradle\gradle-9.7.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon --console=plain 2>&1
$code = $LASTEXITCODE

foreach ($line in $out) { Write-Host $line }

if (-not $NoLog) {
    $out | Out-File -FilePath $logPath -Encoding utf8
}

if ($code -eq 0) {
    Write-Host "TESTS PASSED（gradle exit=0）" -ForegroundColor Green
} else {
    Write-Host "TESTS FAILED（gradle exit=$code）" -ForegroundColor Red
}

# 测试报告位置（HTML 可读失败详情）
Write-Host "报告：shzu_class_km\app\build\reports\tests\testDebugUnitTest\index.html"
