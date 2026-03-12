param(
  [string]$OrchestratorHealthUrl = "http://localhost:8081/actuator/health",
  [string]$ToolsHealthUrl = "http://localhost:8082/actuator/health",
  [int]$MaxAttempts = 60,
  [int]$SleepSeconds = 2
)

for ($i = 0; $i -lt $MaxAttempts; $i++) {
  try {
    $o = (Invoke-RestMethod -Uri $OrchestratorHealthUrl).status
    $t = (Invoke-RestMethod -Uri $ToolsHealthUrl).status
    if ($o -eq 'UP' -and $t -eq 'UP') {
      Write-Host 'Services are healthy'
      exit 0
    }
  } catch {
    # Keep retrying until timeout
  }

  Start-Sleep -Seconds $SleepSeconds
}

throw 'Services did not become healthy in time'