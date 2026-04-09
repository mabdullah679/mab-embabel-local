param(
  [string]$OrchestratorHealthUrl = "http://localhost:8081/actuator/health",
  [int]$MaxAttempts = 60,
  [int]$SleepSeconds = 2
)

for ($i = 0; $i -lt $MaxAttempts; $i++) {
  try {
    $o = (Invoke-RestMethod -Uri $OrchestratorHealthUrl).status
    if ($o -eq 'UP') {
      Write-Host 'Services are healthy'
      exit 0
    }
  } catch {
    # Keep retrying until timeout
  }

  Start-Sleep -Seconds $SleepSeconds
}

throw 'Services did not become healthy in time'
