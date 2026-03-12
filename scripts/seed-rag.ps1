param(
  [string]$ToolsUrl = "http://localhost:8082"
)

$docs = @(
  "Spring MCP tools are orchestrated by a planner agent in this local architecture.",
  "The orchestrator receives natural language queries and routes them to typed tools.",
  "RAG retrieval uses pgvector similarity search with embeddings from nomic-embed-text."
)

foreach ($doc in $docs) {
  $body = @{ content = $doc } | ConvertTo-Json
  $response = Invoke-RestMethod -Method Post -Uri "$ToolsUrl/api/rag/ingest" -ContentType "application/json" -Body $body
  Write-Host "Seeded RAG document:" $response.id
}
