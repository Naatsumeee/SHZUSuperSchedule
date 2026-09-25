<#
.SYNOPSIS
    石大超级课表 APK 构建脚本（唯一入口）。

.DESCRIPTION
    本脚本取代原先散在 tools/ 下的 25 个 build_km_v*.ps1 与 2 个 Debug 脚本。

    那 27 个文件的内容**逐字节相同**，唯一差别只有最后一行日志文件名里的版本号 ——
    它们都是 `sed 's/v151/v152/g' build_km_v151.ps1 > build_km_v152.ps1` 这样派生出来的。
    每出一个版本就多一份副本，导致改一处环境变量要改 27 遍（实际就是漏改的来源）。
    现在版本号改成参数，一份脚本覆盖全部用法。

.PARAMETER Version
    版本标签，用于日志文件名，例如 `-Version 159` 或 `-Version v159`（前导 v 可省）。
    省略时用当前时间戳。日志固定落在仓库根目录 `km_build_v<Version>.txt`。

.PARAMETER Variant
    `Release`（默认）或 `Debug`，对应 assembleRelease / assembleDebug。
    Debug 的日志名为 `km_build_v<Version>_debug.txt`。

.PARAMETER NoLog
    只输出到控制台，不写日志文件。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\build_km.ps1 -Version 160
    构建 Release，日志写 km_build_v160.txt。

.EXAMPLE
    .\tools\build_km.ps1 -Variant Debug
    构建 Debug，日志用时间戳命名。

.NOTES
    ## ⚠️ 本文件必须保存为「UTF-8 with BOM」

    这不是洁癖，是实测踩出来的：Windows PowerShell 5.1 读取**无 BOM** 的 `.ps1` 时
    会按系统 ANSI 代码页（中文 Windows 上是 GBK）解码，UTF-8 的中文注释直接变乱码。
    乱码本身只是难看，但**错位的多字节序列可能把 `{` `}` 这类字节当成 GBK 的合法尾字节吞掉**，
    于是花括号不再配对 → 脚本一秒内解析失败，而且**一行错误信息都不输出**，
    只表现为「执行失败、无任何输出」，极难定位（本次就卡在这里）。

    原先那批 build_km_v*.ps1 是纯 ASCII，所以从没暴露这个问题；
    现在注释改成中文，BOM 就成了必需项。用记事本 / VS Code 保存时请确认编码
    是 `UTF-8 with BOM`（VS Code 里显示为 `UTF-8 with BOM`，不是 `UTF-8`）。

    ## 为什么日志要显式写成 UTF-8

    原脚本用 `... | Tee-Object -FilePath x.txt`，而 Windows PowerShell 5.1 的
    `Tee-Object` **没有 -Encoding 参数**，落盘固定为 **UTF-16**。
    后果是 `grep -q "BUILD SUCCESSFUL" km_build_v13.txt` 会**误判为失败**
    （UTF-16 里每个 ASCII 字符间夹着 \0），排查构建时白绕好几圈。

    这里改成「先捕获再显式 `Out-File -Encoding utf8`」，日志就是普通 UTF-8，
    grep / sed / tr 都能直接读，不再需要 `tr -d '\000'` 那种绕法。

    ## 退出码与 APK 指纹

    结尾打印 APK 路径与**字节数**并回传 Gradle 的退出码，可直接用于自动化判断。
    字节数是刻意打出来的：MIUI 上 `adb install -r` 不保证立刻替换 APK，
    核对设备 `pm path` 得到的 base.apk 大小与本地产物是否一致，是最可靠的验证手段。
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [ValidateSet("Release", "Debug")]
    [string]$Variant = "Release",
    [switch]$NoLog
)

$ErrorActionPreference = "Continue"

# ---------- 环境 ----------
# 工具链用绝对路径（位置固定）；**工程根由脚本自身位置推出**，
# 这样工程被移动到别处、或存在多份工作副本时，脚本仍作用于自己所在的那一份，
# 不会出现「改了 A 目录、构建的却是 B 目录」。
# ⚠️ PS 5.1 下 $PSScriptRoot 在某些调用方式中为空，必须显式兜底，
#    否则 Join-Path 会因空路径直接报错。
$base = "C:\Users\xutia\WorkBuddy\android-toolchain"
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$repo = Split-Path -Parent $scriptDir
$proj = Join-Path $repo "shzu_class_km"

$env:JAVA_HOME = "$base\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$base\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$base\gradle-home"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 代理会干扰 Gradle 拉依赖，且本机并不需要。
foreach ($p in "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy") {
    Remove-Item "Env:$p" -ErrorAction SilentlyContinue
}

# ---------- 参数整理 ----------
# 版本号去掉前导 v，这样 -Version v159 与 -Version 159 得到同一个日志名。
$tag = $Version.Trim() -replace '^[vV]', ''
if ([string]::IsNullOrWhiteSpace($tag)) {
    $tag = Get-Date -Format 'yyyyMMdd_HHmmss'
}

$isDebug = $Variant -eq "Debug"
$task = if ($isDebug) { "assembleDebug" } else { "assembleRelease" }
$suffix = if ($isDebug) { "_debug" } else { "" }
# 始终以 km_build_v 开头：.gitignore 的 `km_build_v*.txt` 才能盖住它，避免误提交构建日志。
$logPath = Join-Path $repo "km_build_v$tag$suffix.txt"

Write-Host "== 构建 $Variant（日志：$(Split-Path $logPath -Leaf)）==" -ForegroundColor Cyan

Set-Location $proj

# ---------- 构建 ----------
# 捕获到变量再分别输出：控制台照常显示，同时能以 UTF-8 落盘（见 .NOTES）。
$out = & "$base\gradle\gradle-9.7.1\bin\gradle.bat" $task --no-daemon --console=plain 2>&1
$code = $LASTEXITCODE

foreach ($line in $out) { Write-Host $line }

if (-not $NoLog) {
    $out | Out-File -FilePath $logPath -Encoding utf8
}

# ---------- 结果 ----------
$text = $out | Out-String
$ok = $text -match "BUILD SUCCESSFUL"

Write-Host ""
if ($ok) {
    Write-Host "BUILD SUCCESSFUL（$Variant, gradle exit=$code）" -ForegroundColor Green
} else {
    Write-Host "BUILD FAILED（$Variant, gradle exit=$code）" -ForegroundColor Red
    # 只挑编译错误，省得在一千行输出里翻。
    $errs = $out | Where-Object { $_ -match "^e: " }
    if ($errs) {
        Write-Host "--- 编译错误 ---" -ForegroundColor Yellow
        $errs | ForEach-Object { Write-Host $_ }
    }
}

# APK 指纹：装到设备后要用它核对是否真的替换成功（见 .NOTES）。
$apkDir = if ($isDebug) { "debug" } else { "release" }
$apk = Join-Path $proj "app\build\outputs\apk\$apkDir\app-$apkDir.apk"
if (Test-Path $apk) {
    Write-Host "APK: $apk"
    Write-Host "大小: $((Get-Item $apk).Length) 字节"
} else {
    Write-Host "APK 未生成：$apk" -ForegroundColor Yellow
}

if (-not $NoLog) { Write-Host "日志: $logPath" }

# 刻意**不写 `exit $code`**。
# Gradle 的退出码已留在 $LASTEXITCODE 里，脚本正常结束后调用方读它即可；
# 而 `exit` 会把宿主 PowerShell 进程直接干掉 —— 实测在自动化调用下
# **整个脚本的输出（含上面这些 Write-Host）全部丢失**，只看到「失败且无输出」，
# 反倒让人以为脚本有语法错误。原 build_km_v*.ps1 没有 exit，所以一直没暴露这个问题。
