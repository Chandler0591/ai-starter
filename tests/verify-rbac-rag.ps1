# ===
# 专项验证：数据级越权（TC-AUTH-10）与向量重建不产生重复片段（TC-RAG-06）。
# 两个用例都不依赖固定 ID：会话取对端最新一条，文档取列表第一条。
# 跑法：cd e:\codes\ai-starter\tests ; .\verify-rbac-rag.ps1
#
# @author Flynn
# @date 2026-10-27
# ===
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'

# 登录拿令牌；需 admin（跨会话看得到）与 user（越权方）两个身份
function Login($u, $p) {
    $body = "{`"username`":`"$u`",`"password`":`"$p`"}"
    return (Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing)
}
$admin = Login 'admin' 'admin123'
$user  = Login 'user'  'user123'
$hA = @{ Authorization = "Bearer $($admin.token)" }
$hU = @{ Authorization = "Bearer $($user.token)" }

# 越权读他人会话消息：期望 403，且错误体带 {status,message,traceId}
Write-Host '=== TC-AUTH-10: user reads admin session messages ==='
$sessions = Invoke-RestMethod -Uri "$base/api/chat/sessions" -Headers $hA -UseBasicParsing
$adminSid = $sessions[0].id
try {
    Invoke-RestMethod -Uri "$base/api/chat/sessions/$adminSid/messages" -Headers $hU -UseBasicParsing | Out-Null
    Write-Host 'UNEXPECTED: 200'
} catch {
    Write-Host "status=$($_.Exception.Response.StatusCode.value__)  body=$((New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd())"
}

# 连续两次重建索引：chunkCount 必须与初值一致（先删后写，不累加）
Write-Host '=== TC-RAG-06: reindex twice, vector rows must stay stable ==='
$docs = Invoke-RestMethod -Uri "$base/api/knowledge" -Headers $hA -UseBasicParsing
$docId = $docs[0].id
Write-Host "docId=$docId chunks(before)=$($docs[0].chunkCount)"
Invoke-RestMethod -Uri "$base/api/knowledge/$docId/reindex" -Method Post -Headers $hA -UseBasicParsing | Out-Null
$after1 = (Invoke-RestMethod -Uri "$base/api/knowledge" -Headers $hA -UseBasicParsing) | Where-Object { $_.id -eq $docId }
Invoke-RestMethod -Uri "$base/api/knowledge/$docId/reindex" -Method Post -Headers $hA -UseBasicParsing | Out-Null
$after2 = (Invoke-RestMethod -Uri "$base/api/knowledge" -Headers $hA -UseBasicParsing) | Where-Object { $_.id -eq $docId }
Write-Host "chunkCount: before=$($docs[0].chunkCount) after1=$($after1.chunkCount) after2=$($after2.chunkCount)"
