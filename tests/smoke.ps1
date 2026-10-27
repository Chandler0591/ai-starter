# ===
# HTTP 冒烟：后端已启动（8080）+ PostgreSQL 健康时跑一遍全链路。
# 11 步覆盖：登录、/me、SSE 对话（RAG 命中 / 工具调用 / 护栏拦截）、RBAC 403、
# 知识库列表、会话列表、看板指标、离线评测、Prometheus ai_* 序列。
# 只读依赖 cases 目录里的语料，不往库里写业务文档（评测除外，评测会新增会话）。
#
# 跑法（PowerShell 5.1，用 ; 不用 &&）：
#   cd e:\codes\ai-starter\tests ; .\smoke.ps1
#
# @author Flynn
# @date 2026-10-27
# ===
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$dir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'cases'

# 把 cases 下的 JSON 语料作为请求体发出去（-N 关掉 curl 缓冲，才能逐帧看 SSE）
function PostFile($path, $token, $file) {
  $curlArgs = @('-s', '-N', '-X', 'POST', "$base$path",
    '-H', 'Content-Type: application/json; charset=UTF-8',
    '--data-binary', "@$dir\$file")
  if ($token) { $curlArgs += @('-H', "Authorization: Bearer $token") }
  & curl.exe @curlArgs
  Write-Host ''
}

Write-Host '=== 1) login admin ==='
$login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' `
  -Body '{"username":"admin","password":"admin123"}'
$adminToken = $login.token
Write-Host "role=$($login.role) expires=$($login.expiresInSeconds)s token=$($adminToken.Substring(0,24))..."
$userLogin = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' `
  -Body '{"username":"user","password":"user123"}'
$userToken = $userLogin.token
Write-Host "role=$($userLogin.role) token ok"

Write-Host '=== 2) /api/auth/me ==='
Invoke-RestMethod -Uri "$base/api/auth/me" -Headers @{ Authorization = "Bearer $adminToken" } | ConvertTo-Json -Compress

Write-Host '=== 3) SSE chat, RAG hit (admin) ==='
PostFile '/api/chat/stream' $adminToken 'case-rag.json'

Write-Host '=== 4) SSE chat, tool call my orders (user) ==='
PostFile '/api/chat/stream' $userToken 'case-order.json'

Write-Host '=== 5) guardrail blocked ==='
PostFile '/api/chat/stream' $adminToken 'case-guardrail.json'

Write-Host '=== 6) RBAC: USER hitting ADMIN-only api should be 403 ==='
$code = & curl.exe -s -o NUL -w '%{http_code}' -X POST "$base/api/knowledge" `
  -H 'Content-Type: application/json' -H "Authorization: Bearer $userToken" `
  --data-binary '{"title":"t","content":"c"}'
Write-Host "POST /api/knowledge as USER -> HTTP $code (expect 403)"

Write-Host '=== 7) knowledge list ==='
Invoke-RestMethod -Uri "$base/api/knowledge" -Headers @{ Authorization = "Bearer $adminToken" } | ConvertTo-Json -Compress

Write-Host '=== 8) sessions of admin ==='
Invoke-RestMethod -Uri "$base/api/chat/sessions" -Headers @{ Authorization = "Bearer $adminToken" } | ConvertTo-Json -Compress

Write-Host '=== 9) dashboard metrics ==='
$dash = Invoke-RestMethod -Uri "$base/api/dashboard/metrics" -Headers @{ Authorization = "Bearer $adminToken" }
Write-Host "provider=$($dash.provider) docs=$($dash.knowledgeDocs) metricRows=$($dash.metrics.Count)"
$dash.metrics | Where-Object { $_.name -like 'ai.*' } | ForEach-Object {
  Write-Host ("  {0} tags={1} values={2}" -f $_.name, ($_.tags | ConvertTo-Json -Compress), ($_.values | ConvertTo-Json -Compress))
}

Write-Host '=== 10) run eval ==='
$report = Invoke-RestMethod -Uri "$base/api/eval/run" -Method Post -Headers @{ Authorization = "Bearer $adminToken" }
Write-Host "provider=$($report.provider) passed=$($report.passed)/$($report.total)"
$report.results | ForEach-Object { Write-Host "  id=$($_.id) passed=$($_.passed)" }

Write-Host '=== 11) prometheus ai_* series ==='
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
(Invoke-WebRequest -UseBasicParsing -Uri "$base/actuator/prometheus" -Headers @{ Authorization = "Bearer $adminToken" }).Content `
  -split "`n" | Where-Object { $_ -match '^ai_' } | Select-Object -First 25
