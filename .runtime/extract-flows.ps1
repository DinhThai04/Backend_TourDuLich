$logDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'logs'
$out = New-Object System.Collections.Generic.List[string]
foreach ($g in @('auth', 'tour', 'booking', 'extra')) {
    $r = Get-Content (Join-Path $logDir "results-$g.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $out.Add("===== GROUP $g ($($r.Count)) =====") | Out-Null
    for ($i = 0; $i -lt $r.Count; $i++) {
        $x = $r[$i]
        $ok = if ($x.Ok) { 'PASS' } else { 'FAIL' }
        $out.Add(("{0,2}|{1}|{2}|{3} {4}|{5}|{6}" -f ($i + 1), $x.Name, $ok, $x.Method, $x.Path, $x.Status, $x.ElapsedMs)) | Out-Null
    }
}
[IO.File]::WriteAllText((Join-Path $logDir 'flows.txt'), ($out -join "`r`n"), [Text.UTF8Encoding]::new($true))
'flows written'
