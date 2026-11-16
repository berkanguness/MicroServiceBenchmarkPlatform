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
$info = $null
try {
  $info = Invoke-RestMethod "$($ManagementUrl.TrimEnd('/'))/actuator/info" -TimeoutSec 10
  Save-Json (Join-Path $out 'actuator-info.json') $info
} catch {
  [IO.File]::WriteAllText((Join-Path $out 'metadata-unavailable.txt'), 'Actuator info could not be collected. Not a verified benchmark run.', $utf8)
}
if ($Mode -eq 'load' -and $profile -ne 'shakedown') {
  if (!$info -or !$info.build.git.sha -or !$info.lab.dataset.checksumSha256) { throw 'Ölçüm öncesi Actuator sürüm/veri bilgisi gerekli.' }
  if (!$EnvironmentRecord) { throw 'Doruk ile hazırlanan ortam kaydını -EnvironmentRecord ile verin.' }
  $environment = Get-Content -LiteralPath $EnvironmentRecord -Raw | ConvertFrom-Json
  foreach ($key in @('resourceLimits','imageDigests','cachePolicy','protocolOrder','repetition','monitoringMode','equivalenceEvidence','fullVerificationEvidence')) {
    if (!$environment.$key) { throw "Ortam kaydında eksik: $key" }
  }
  Save-Json (Join-Path $out 'environment.json') $environment
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
  }
} finally {
  Save-Json (Join-Path $out 'run.json') @{ runId=$runId; mode=$Mode; protocol=$Protocol; baseUrl=$BaseUrl; exitCode=$exit; startedAt=$startedAt; completedAt=(Get-Date).ToString('o'); k6Version=$version; profile=$env:PROFILE; evidence=$Evidence; note='Exit code alone does not establish equivalence, monitoring overhead or benchmark validity.' }
}
Write-Host "Sonuç klasörü: $out"
if ($exit -ne 0) { throw "k6 exit code: $exit. Sonuçları inceleyin." }
