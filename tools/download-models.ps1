﻿﻿# ═══════════════════════════════════════════════════════════════
# PocketNode 模型下载器
#
# 从 HuggingFace 镜像下载，**不经过自己的服务器**。
#
# 关于源的选择：
#   默认用 hf-mirror.com（国内可直连的 HF 镜像）。
#   想直连官方就加 -Source official（需要能访问 huggingface.co）。
#
# 关于并发策略：
#   四个文件各起一条连接同时下，而不是"一个文件切 8 段再拼接"。
#   分片方案吞吐更高一点，但拼接顺序错了文件就废了，而且模型加载时
#   不会给出有意义的错误 —— 几千 MB 的东西，不值得为那点速度冒险。
#   四个文件本来就是独立的，天然可并行，不需要拼。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File download-models.ps1
#   powershell -ExecutionPolicy Bypass -File download-models.ps1 -Source official
# ═══════════════════════════════════════════════════════════════

# 不用 param() —— PowerShell 5.1 在这个文件上把 param 当成了命令名，
# 报 "The assignment expression is not valid"，怎么调都不对。
# 用普通变量 + $args 覆盖，效果一样，还少一层解析陷阱。
$OutDir = "D:\PocketNodeModels"
$Source = "mirror"

if ($args.Count -ge 1 -and $args[0]) { $OutDir = $args[0] }
if ($args.Count -ge 2 -and $args[1] -eq "official") { $Source = "official" }

$Curl = "$env:SystemRoot\System32\curl.exe"

$Base = if ($Source -eq "official") { "https://huggingface.co" } else { "https://hf-mirror.com" }

# 本地文件名 -> (仓库, 仓库内的路径, 权威字节数)
# 字节数来自 HuggingFace API 的 size 字段，用来校验完整性。
# 之前我用网页上四舍五入的 MB 数反推过，结果全错，导致脚本一直报"大小不符"
# 而实际文件是好的 —— 所以这里必须是精确值。
$Models = [ordered]@{
    "qwen2.5-0.5b-q8.task" = @(
        "litert-community/Qwen2.5-0.5B-Instruct",
        "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        546660344)
    "qwen2.5-1.5b-q8.task" = @(
        "litert-community/Qwen2.5-1.5B-Instruct",
        "Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
        1567364648)
    "deepseek-r1-qwen-1.5b-q8.task" = @(
        "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        1861094737)
    "phi-4-mini-q8.task" = @(
        "litert-community/Phi-4-mini-instruct",
        "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
        3944275882)
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Human([long]$b) {
    if ($b -ge 1GB) { return "{0:N2} GB" -f ($b / 1GB) }
    if ($b -ge 1MB) { return "{0:N1} MB" -f ($b / 1MB) }
    return "$b B"
}

Write-Host "PocketNode 模型下载"
Write-Host "  源    $Base  ($Source)"
Write-Host "  目标  $OutDir"
Write-Host ""

$todo = @()
foreach ($name in $Models.Keys) {
    $spec = $Models[$name]
    $want = [long]$spec[2]
    $target = Join-Path $OutDir $name
    if (Test-Path $target) {
        $have = (Get-Item $target).Length
        if ($have -eq $want) {
            Write-Host ("  [跳过] {0,-34} 已完整 ({1})" -f $name, (Human $have))
            continue
        }
        # 大小不符分两种情况，处理方式**相反**，不能一律删：
        #   偏小 = 没下完       -> 保留，交给 curl -C - 续传
        #   偏大 = 续传出过错    -> 必须删。curl 只能往后追加，
        #                          没法从中间删，偏大的文件续传只会越错越多
        if ($have -gt $want) {
            Write-Host ("  [重下] {0,-34} 偏大 {1} > {2}，续传救不了" -f $name, (Human $have), (Human $want))
            Remove-Item $target -Force
        } else {
            Write-Host ("  [续传] {0,-34} {1} / {2}  ({3:N1}%)" -f $name, (Human $have), (Human $want), ($have * 100.0 / $want))
        }
    } else {
        Write-Host ("  [下载] {0,-34} {1}" -f $name, (Human $want))
    }
    $todo += $name
}

if ($todo.Count -eq 0) {
    Write-Host ""
    Write-Host "全部已完整，无需下载。" -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "开始下载 $($todo.Count) 个文件…"

$jobs = @()
foreach ($name in $todo) {
    $spec = $Models[$name]
    $url = "$Base/" + $spec[0] + "/resolve/main/" + $spec[1]
    $out = Join-Path $OutDir $name
    $jobs += Start-Job -Name $name -ScriptBlock {
        param($curl, $url, $out)
        # -C - 断点续传：断了重跑这个脚本会接着下，不会白下
        # --retry-all-errors：HTTP 5xx 也重试，不只是网络层错误
        & $curl -sL -C - --retry 20 --retry-delay 10 --retry-all-errors --connect-timeout 30 -o $out $url 2>&1 | Out-Null
        if (Test-Path $out) { (Get-Item $out).Length } else { 0 }
    } -ArgumentList $Curl, $url, $out
}

$sw = [Diagnostics.Stopwatch]::StartNew()
$totalWant = 0
foreach ($n in $todo) { $totalWant += [long]$Models[$n][2] }

while ($jobs | Where-Object { $_.State -eq "Running" }) {
    Start-Sleep -Seconds 5
    $done = 0
    foreach ($n in $todo) {
        $f = Join-Path $OutDir $n
        if (Test-Path $f) { $done += (Get-Item $f).Length }
    }
    $pct = if ($totalWant -gt 0) { $done * 100 / $totalWant } else { 0 }
    $spd = if ($sw.Elapsed.TotalSeconds -gt 0) { $done / 1MB / $sw.Elapsed.TotalSeconds } else { 0 }
    $eta = if ($spd -gt 0) { ($totalWant - $done) / 1MB / $spd / 60 } else { 0 }
    Write-Host ("`r  {0,5:N1}%   {1} / {2}   {3:N2} MB/s   剩余约 {4:N0} 分钟   " -f $pct, (Human $done), (Human $totalWant), $spd, $eta) -NoNewline
}
Write-Host ""

$jobs | Wait-Job -Timeout 120 | Out-Null
$jobs | Remove-Job -Force

Write-Host ""
$ok = 0; $bad = 0
foreach ($name in $todo) {
    $target = Join-Path $OutDir $name
    $want = [long]$Models[$name][2]
    if (-not (Test-Path $target)) {
        Write-Host ("  [X] {0}  文件不存在" -f $name) -ForegroundColor Red
        $bad++
        continue
    }
    $have = (Get-Item $target).Length
    if ($have -eq $want) {
        Write-Host ("  [OK] {0,-34} {1}" -f $name, (Human $have)) -ForegroundColor Green
        $ok++
    } else {
        Write-Host ("  [X] {0,-34} 实际 {1} / 期望 {2}" -f $name, (Human $have), (Human $want)) -ForegroundColor Red
        $bad++
    }
}

Write-Host ""
if ($bad -eq 0) {
    Write-Host "全部完成，$ok 个文件校验通过" -ForegroundColor Green
} else {
    Write-Host "成功 $ok / 失败 $bad" -ForegroundColor Yellow
    Write-Host "失败的重新跑一遍脚本即可，会自动续传。"
}
Write-Host ""
Write-Host "下一步：把 $OutDir 里的 .task 拷到手机的"
Write-Host "/storage/emulated/0/PNAI/ 目录，App 模型页会自动识别。"
