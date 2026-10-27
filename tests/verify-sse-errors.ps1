# ===
# 专项验证：SSE 编排前置阶段（prepare）出错时，必须以 error 帧收尾而不是直接断流。
# 对应测试计划里的 TC-CHAT-09（会话不存在）与 TC-CHAT-10（越权访问他人会话），
# 也就是缺陷 B9 的回归用例：修复前这两条会让前端只看到一个崩断的流。
#
# 判定只看帧结构（"error":true / "done":true）与 curl 退出码，不在脚本里比较中文文案，
# 避免控制台编码差异造成误判；原始帧会整段打印出来供人工确认。
#
# 跑法（需后端已启动）：cd e:\codes\ai-starter\tests ; .\verify-sse-errors.ps1
#
# @author Flynn
# @date 2026-10-27
# ===
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'

function Login($u, $p) {
    $body = "{`"username`":`"$u`",`"password`":`"$p`"}"
    return (Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing)
}

# 读 ai.degradation{stage="prepare"} 计数，用于确认兜底分支真的被走到（Prometheus 文本格式）
function Get-PrepareDegradation($token) {
    $text = (Invoke-WebRequest -UseBasicParsing -Uri "$base/actuator/prometheus" -Headers @{ Authorization = "Bearer $token" }).Content
    $line = ($text -split "`n") | Where-Object { $_ -match 'ai_degradation_total\{[^#]*stage="prepare"' } | Select-Object -Last 1
    if (-not $line) { return 0 }
    return [double]($line.Trim() -replace '.*\s', '')
}

# 用 curl 发 SSE 请求：语料写临时文件，保证 UTF-8 无 BOM 且不受控制台编码影响
function Invoke-Stream($token, $jsonBody) {
    $tmp = Join-Path $env:TEMP 'ai-starter-sse-body.json'
    [System.IO.File]::WriteAllText($tmp, $jsonBody, (New-Object System.Text.UTF8Encoding($false)))
    $out = & curl.exe -s -N -X POST "$base/api/chat/stream" `
        -H 'Content-Type: application/json; charset=UTF-8' `
        -H "Authorization: Bearer $token" `
        --data-binary "@$tmp"
    return @{ Exit = $LASTEXITCODE; Text = ($out -join "`n") }
}

# 两条用例共用的断言：连接正常结束（exit=0）且末帧同时带 done 与 error 标记
function Assert-ErrorFrame($name, $result) {
    $hasErrorFrame = $result.Text -match '"error":\s*true' -and $result.Text -match '"done":\s*true'
    $ok = ($result.Exit -eq 0) -and $hasErrorFrame
    Write-Host ("[{0}] {1}  curlExit={2} errorFrame={3}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $result.Exit, $hasErrorFrame)
    Write-Host ($result.Text -split "`n" | Select-Object -Last 2 | ForEach-Object { "    $_" })
    return $ok
}

$admin = Login 'admin' 'admin123'
$user = Login 'user' 'user123'
# /actuator/** 只对 ADMIN 开放，所以读指标用 admin 令牌，发请求用 user 令牌
$before = Get-PrepareDegradation $admin.token

Write-Host '=== TC-CHAT-09: stream with a nonexistent sessionId ==='
$r1 = Invoke-Stream $user.token '{"message":"hello","sessionId":999999}'
$ok1 = Assert-ErrorFrame 'TC-CHAT-09' $r1

Write-Host '=== TC-CHAT-10: stream into someone else''s session ==='
$sessions = Invoke-RestMethod -Uri "$base/api/chat/sessions" -Headers @{ Authorization = "Bearer $($admin.token)" } -UseBasicParsing
$adminSid = $sessions[0].id
Write-Host "target sessionId=$adminSid (owned by admin, requested as user)"
$r2 = Invoke-Stream $user.token "{`"message`":`"hello`",`"sessionId`":$adminSid}"
$ok2 = Assert-ErrorFrame 'TC-CHAT-10' $r2

# 两条用例各应打一次 prepare 降级点；这里只打印增量，不硬断言（同一 JVM 里可能被其他请求复用计数）
$after = Get-PrepareDegradation $admin.token
Write-Host "ai_degradation_total{stage=`"prepare`"}: before=$before after=$after delta=$($after - $before)"

if ($ok1 -and $ok2) { Write-Host 'ALL PASS'; exit 0 } else { Write-Host 'SOME CASE FAILED'; exit 1 }
