$ErrorActionPreference="Stop"
$t=$null;$e=$null
$target = Join-Path $PSScriptRoot "download-models.ps1"
[System.Management.Automation.Language.Parser]::ParseFile($target,[ref]$t,[ref]$e) | Out-Null
if($e.Count -eq 0){"语法 OK"}else{$e | Select-Object -First 3 | ForEach-Object {$_.Message}}