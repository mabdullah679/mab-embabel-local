param(
  [string]$ToolsPort = "18082",
  [string]$OrchestratorPort = "18081"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$toolsLog = Join-Path $root "temp-tools-mcp.log"
$toolsErr = Join-Path $root "temp-tools-mcp.err.log"
$orchLog = Join-Path $root "temp-orchestrator-mcp.log"
$orchErr = Join-Path $root "temp-orchestrator-mcp.err.log"

Remove-Item $toolsLog,$toolsErr,$orchLog,$orchErr -ErrorAction SilentlyContinue

$toolsProc = $null
$orchProc = $null

function Wait-ForHttp($url) {
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $resp = Invoke-RestMethod -Uri $url -Method Get
      if ($resp.status -eq "UP") {
        return
      }
    } catch {
    }
    Start-Sleep -Seconds 1
  }
  throw "Timed out waiting for $url"
}

try {
  $toolsProc = Start-Process java -PassThru `
    -WorkingDirectory $root `
    -RedirectStandardOutput $toolsLog `
    -RedirectStandardError $toolsErr `
    -ArgumentList @(
      "-jar", "tools-app\target\tools-app-0.1.0-SNAPSHOT.jar",
      "--server.port=$ToolsPort",
      "--spring.datasource.url=jdbc:postgresql://localhost:5432/mab",
      "--spring.datasource.username=mab",
      "--spring.datasource.password=mab",
      "--ollama.base-url=http://localhost:11434"
    )

  Wait-ForHttp "http://localhost:$ToolsPort/actuator/health"

  $orchProc = Start-Process java -PassThru `
    -WorkingDirectory $root `
    -RedirectStandardOutput $orchLog `
    -RedirectStandardError $orchErr `
    -ArgumentList @(
      "-jar", "orchestrator-app\target\orchestrator-app-0.1.0-SNAPSHOT.jar",
      "--server.port=$OrchestratorPort",
      "--tools.base-url=http://localhost:$ToolsPort",
      "--spring.ai.mcp.client.sse.connections.tools.url=http://localhost:$ToolsPort",
      "--ollama.base-url=http://localhost:11434"
    )

  Wait-ForHttp "http://localhost:$OrchestratorPort/actuator/health"

  $queryBody = @{ query = "schedule a meeting tomorrow at 3pm with sam and joe" } | ConvertTo-Json
  $response = Invoke-RestMethod -Method Post -Uri "http://localhost:$OrchestratorPort/agent/query" -ContentType "application/json" -Body $queryBody
  $response | ConvertTo-Json -Depth 8
}
finally {
  if ($orchProc -and !$orchProc.HasExited) {
    Stop-Process -Id $orchProc.Id -Force
  }
  if ($toolsProc -and !$toolsProc.HasExited) {
    Stop-Process -Id $toolsProc.Id -Force
  }
}
