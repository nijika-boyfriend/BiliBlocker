param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$adbArgs = @()
if ($Serial) {
    $adbArgs += @("-s", $Serial)
}

& adb @adbArgs logcat -d -v time | Select-String "BiliBlocker|LSPosedFramework"
