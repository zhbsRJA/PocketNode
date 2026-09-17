$ErrorActionPreference="Stop"
$t=$null;$e=$null
[System.Management.Automation.Language.Parser]::ParseFile("D:\AndroidProjects\PocketNode\tools\download-models.ps1",[ref]$t,[ref]$e) | Out-Null
if($e.Count -eq 0){"语法 OK"}else{$e | Select-Object -First 3 | ForEach-Object {$_.Message}}