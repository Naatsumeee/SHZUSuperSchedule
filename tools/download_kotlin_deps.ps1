$ErrorActionPreference = "Continue"
$ver = "2.4.20"
$artifacts = @(
    "kotlin-compiler-embeddable",
    "kotlin-build-tools-impl",
    "kotlin-build-tools-compat",
    "kotlin-build-tools-cri-impl",
    "kotlin-script-runtime",
    "kotlin-daemon-embeddable",
    "kotlin-reflect",
    "kotlin-stdlib",
    "kotlin-stdlib-common",
    "annotations"
)
$groupPath = "C:\Users\xutia\WorkBuddy\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin"
$log = @()

foreach ($a in $artifacts) {
    $url = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/$a/$ver/$a-$ver.jar"
    $tmp = "C:\Users\xutia\WorkBuddy\android-toolchain\downloads\kt-$a.jar"
    curl.exe -k -s -L --noproxy "*" --max-time 300 -o $tmp $url
    if ((Test-Path $tmp) -and ((Get-Item $tmp).Length -gt 1000)) {
        $sha1 = (Get-FileHash $tmp -Algorithm SHA1).Hash.ToLower()
        $dir = "$groupPath\$a\$ver\$sha1"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Copy-Item $tmp "$dir\$a-$ver.jar" -Force
        $log += ("OK   {0} ({1:N1} MB)" -f $a, ((Get-Item $tmp).Length / 1MB))
    } else {
        $log += "FAIL $a"
    }
}
$log -join "`n" | Out-File "C:\Users\xutia\WorkBuddy\SHZUClassList\kt_download.txt" -Encoding utf8
Write-Output "KT_DONE"
