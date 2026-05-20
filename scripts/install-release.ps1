param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $repoRoot "BiliBlocker.apk"

Push-Location $repoRoot
try {
    & .\gradlew.bat assembleRelease

    if (-not (Test-Path $apkPath)) {
        throw "APK not found: $apkPath"
    }

    $adbArgs = @()
    if ($Serial) {
        $adbArgs += @("-s", $Serial)
    }

    & adb @adbArgs install -r $apkPath
    & adb @adbArgs shell am force-stop tv.danmaku.bili
}
finally {
    Pop-Location
}
