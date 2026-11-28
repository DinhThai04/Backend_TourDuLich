$logDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'logs'
# Pick by 0-based index into each group's result array (order matches the run log).
$picks = [ordered]@{
    'booking' = @(0, 28, 29, 30, 31, 32, 35, 38, 21, 15, 18, 51, 42)
    'extra'   = @(4)
    'auth'    = @(33, 5)
    'tour'    = @(22)
}
$out = New-Object System.Collections.Generic.List[string]
foreach ($g in $picks.Keys) {
    # PS 5.1 keeps the JSON array as a single object; index it directly.
    $r = Get-Content (Join-Path $logDir "results-$g.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($r -isnot [System.Array]) { $r = @($r) }
    foreach ($idx in $picks[$g]) {
        if ($idx -ge $r.Count) { continue }
        $x = $r[$idx]
        $out.Add("##### $g[$idx] :: $($x.Name)") | Out-Null
        $out.Add("REQ: $($x.Request)") | Out-Null
        $resp = [string]$x.Response
        if ($resp.Length -gt 1100) { $resp = $resp.Substring(0, 1100) + '...' }
        $out.Add("RES: $resp") | Out-Null
        $out.Add('') | Out-Null
    }
}
[IO.File]::WriteAllText((Join-Path $logDir 'snippets.txt'), ($out -join "`r`n"), [Text.UTF8Encoding]::new($true))
"snippets written: $($out.Count) lines"
