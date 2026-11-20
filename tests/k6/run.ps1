param(
  [ValidateSet('smoke','load')][string]$Mode = 'smoke',
  [ValidateSet('rest','graphql','both')][string]$Protocol = 'rest',
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [string]$ManagementUrl = 'http://127.0.0.1:8081',
  [string]$K6 = 'k6',
  [ValidateSet('backend','mock-only')][string]$Evidence = 'backend',
  [string]$EnvironmentRecord
)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'load' -and $Protocol -eq 'both') { throw 'Yük testi tek protokolle çalışır: rest veya graphql.' }
$profile = if ($env:PROFILE) { $env:PROFILE } else { 'shakedown' }
$runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$startedAt = (Get-Date).ToString('o')
$out = Join-Path $PSScriptRoot "results/$runId-$Mode-$Protocol"
New-Item -ItemType Directory -Path $out | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Save-Json($path, $value) { [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 50), $utf8) }
function Assert-AbsoluteEndpoint($name, $value) {
  try { $uri = [Uri]$value } catch { throw "$name geçerli mutlak bir URL olmalı: $value" }
  if (!$uri.IsAbsoluteUri -or $uri.Scheme -notin @('http','https')) { throw "$name yalnız http/https mutlak URL olmalı: $value" }
}
Assert-AbsoluteEndpoint 'BaseUrl' $BaseUrl
Assert-AbsoluteEndpoint 'ManagementUrl' $ManagementUrl

$official = $Mode -eq 'load' -and $profile -ne 'shakedown'
$info = $null
try {
  $info = Invoke-RestMethod "$($ManagementUrl.TrimEnd('/'))/actuator/info" -TimeoutSec 10
  Save-Json (Join-Path $out 'actuator-info.json') $info
} catch {
  [IO.File]::WriteAllText((Join-Path $out 'metadata-unavailable.txt'), 'Actuator info could not be collected. Not a verified benchmark run.', $utf8)
}
if ($official) {
  if ($Evidence -ne 'backend') { throw 'Resmi ölçüm Evidence=mock-only olamaz.' }
  if (!$info -or !$info.build.git.sha -or !$info.lab.dataset.checksumSha256) { throw 'Ölçüm öncesi Actuator sürüm/veri bilgisi gerekli.' }
  $sha = [string]$info.build.git.sha
  $checksum = [string]$info.lab.dataset.checksumSha256
  if ($sha -eq 'unknown' -or $sha -notmatch '^[0-9a-fA-F]{7,40}$') { throw 'Actuator build.git.sha geçerli bir Git SHA değil.' }
  if ($checksum -notmatch '^[0-9a-fA-F]{64}$') { throw 'Actuator lab.dataset.checksumSha256 geçerli bir SHA-256 değil.' }
  $localSha = (& git rev-parse HEAD 2>$null | Out-String).Trim()
  if ($LASTEXITCODE -ne 0 -or !$localSha) { throw 'Aktif depo Git SHA okunamadı.' }
  if (!$localSha.StartsWith($sha, [StringComparison]::OrdinalIgnoreCase) -and !$sha.StartsWith($localSha, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Git SHA uyuşmazlığı: depo=$localSha actuator=$sha"
  }
  if (!$EnvironmentRecord) { throw 'Doruk ile hazırlanan ortam kaydını -EnvironmentRecord ile verin.' }
  $environment = Get-Content -LiteralPath $EnvironmentRecord -Raw | ConvertFrom-Json
  foreach ($key in @('resourceLimits','imageDigests','cachePolicy','protocolOrder','repetition','monitoringMode','equivalenceEvidence','fullVerificationEvidence')) {
    if (!$environment.$key) { throw "Ortam kaydında eksik: $key" }
  }
  Save-Json (Join-Path $out 'environment.json') $environment

  try {
    $ready = Invoke-WebRequest "$($ManagementUrl.TrimEnd('/'))/actuator/health/readiness" -TimeoutSec 10
    if ($ready.StatusCode -lt 200 -or $ready.StatusCode -ge 300) { throw "Readiness status=$($ready.StatusCode)" }
  } catch { throw "Readiness ön kontrolü başarısız: $($_.Exception.Message)" }

  try {
    if ($Protocol -eq 'rest') {
      $probe = Invoke-WebRequest "$($BaseUrl.TrimEnd('/'))/api/v1/patients/3/summary" -TimeoutSec 10
      $probeContentType = [string]$probe.Headers['Content-Type']
      if ($probe.StatusCode -ne 200 -or $probeContentType -notmatch 'application/json') { throw "REST ön kontrolü status=$($probe.StatusCode) content-type=$probeContentType" }
      $probeBodyPath = Join-Path $out 'preflight-rest-body.json'
      [IO.File]::WriteAllText($probeBodyPath, [string]$probe.Content, $utf8)
      & node (Join-Path $PSScriptRoot 'preflight.mjs') rest ([string]$probe.StatusCode) $probeContentType $probeBodyPath
      if ($LASTEXITCODE -ne 0) { throw 'REST ön kontrol sözleşme doğrulaması başarısız.' }
    } elseif ($Protocol -eq 'graphql') {
      $body = '{"query":"query QAPreflight($id: ID!) { patientSummary(patientId: $id) { patient { id } } }","operationName":"QAPreflight","variables":{"id":"3"}}'
      $probe = Invoke-WebRequest "$($BaseUrl.TrimEnd('/'))/graphql" -Method Post -ContentType 'application/json' -Headers @{ Accept='application/json' } -Body $body -TimeoutSec 10
      $probeContentType = [string]$probe.Headers['Content-Type']
      if ($probe.StatusCode -ne 200 -or $probeContentType -notmatch 'application/json|application/graphql-response\+json') { throw "GraphQL ön kontrolü status=$($probe.StatusCode) content-type=$probeContentType" }
      $probeBodyPath = Join-Path $out 'preflight-graphql-body.json'
      [IO.File]::WriteAllText($probeBodyPath, [string]$probe.Content, $utf8)
      & node (Join-Path $PSScriptRoot 'preflight.mjs') graphql ([string]$probe.StatusCode) $probeContentType $probeBodyPath
      if ($LASTEXITCODE -ne 0) { throw 'GraphQL ön kontrol sözleşme doğrulaması başarısız.' }
    }
  } catch { throw "Uç nokta ön kontrolü başarısız: $($_.Exception.Message)" }
}
$version = (& $K6 version | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'k6 version komutu başarısız.' }
$arguments = @('run','--out',"json=$(Join-Path $out 'raw.json')",'-e',"PROFILE=$profile",'-e',"EVIDENCE=$Evidence",'-e',"RUN_ID=$runId",'-e',"PROTOCOL=$Protocol",'-e',"BASE_URL=$BaseUrl",'-e',"SUMMARY_PATH=$(Join-Path $out 'summary.json')")
if ($info.build.git.sha) { $arguments += @('-e',"GIT_SHA=$($info.build.git.sha)") }
if ($info.lab.dataset.checksumSha256) { $arguments += @('-e',"DATASET_CHECKSUM=$($info.lab.dataset.checksumSha256)") }
$arguments += (Join-Path $PSScriptRoot "$Mode.js")
$exit = 1
try {
  & $K6 @arguments 2>&1 | Tee-Object -FilePath (Join-Path $out 'console.log')
  $exit = $LASTEXITCODE
  if ($Mode -eq 'load' -and $exit -eq 0) {
    $summary = Get-Content -LiteralPath (Join-Path $out 'summary.json') -Raw | ConvertFrom-Json
    if ($summary.status -ne 'completed') { $exit = 1 }
    if ($official) {
      & node (Join-Path $PSScriptRoot 'summarize.mjs') --validate (Join-Path $out 'summary.json')
      if ($LASTEXITCODE -ne 0) { $exit = 1 }
    }
  }
} finally {
  Save-Json (Join-Path $out 'run.json') @{ runId=$runId; mode=$Mode; protocol=$Protocol; baseUrl=$BaseUrl; exitCode=$exit; startedAt=$startedAt; completedAt=(Get-Date).ToString('o'); k6Version=$version; profile=$env:PROFILE; evidence=$Evidence; note='Exit code alone does not establish equivalence, monitoring overhead or benchmark validity.' }
}
Write-Host "Sonuç klasörü: $out"
if ($exit -ne 0) { throw "k6 exit code: $exit. Sonuçları inceleyin." }
