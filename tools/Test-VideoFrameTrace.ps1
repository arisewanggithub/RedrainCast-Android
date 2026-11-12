$ErrorActionPreference = 'Stop'
$directory = [IO.Directory]::CreateDirectory((Join-Path ([IO.Path]::GetTempPath()) ("RedrainCast-trace-test-" + [guid]::NewGuid().ToString('N'))))
$sender = Join-Path $directory.FullName 'sender.jsonl'
$receiver = Join-Path $directory.FullName 'receiver.jsonl'
$analyzer = Join-Path $PSScriptRoot 'Analyze-VideoFrameTrace.ps1'
function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Write-Trace([string]$Path, [long]$Session, $Events, [int]$Dropped = 0) {
    $rows = @(@{ kind = 'header'; session = $Session; frequency = 1000000000; capacity = 16384 })
    $rows += $Events
    $rows += @{ kind = 'summary'; events = $Events.Count; dropped = $Dropped; windowComplete = ($Dropped -eq 0) }
    $rows | ForEach-Object { $_ | ConvertTo-Json -Compress } | Set-Content -LiteralPath $Path -Encoding UTF8
}
function Event([string]$Stage, [long]$Pts, [int]$Generation = 1) {
    @{ kind = 'event'; stage = $Stage; ptsUs = $Pts; generation = $Generation; atNs = $Pts * 1000 }
}
try {
    $events = @()
    foreach ($pts in @(0, 1000000, 1000000, 2000000, 3000000)) { $events += Event 'SUBMIT_END' $pts 0 }
    foreach ($pts in @(0, 1000000, 3000000)) {
        $events += Event 'ENCODED' $pts 1
        $begin = Event 'WRITE_BEGIN' $pts 2
        $begin.atNs -= 2000000
        $events += $begin
        $events += Event 'WRITE_END' $pts 2
    }
    Write-Trace $sender 77 $events
    $received = @()
    foreach ($stage in @('RECEIVED', 'DECODED', 'PRESENTED')) {
        foreach ($pts in @(0, 3000000)) { $received += Event $stage $pts 2 }
    }
    Write-Trace $receiver 77 $received
    $report = & $analyzer -SenderTrace $sender -ReceiverTrace $receiver | ConvertFrom-Json
    $capture = @($report.Continuity | Where-Object { $_.From -eq 'SUBMIT_END' })[0]
    Assert ($capture.MissingOccurrences -eq 2) 'Expected a missing duplicate plus one missing encoder picture.'
    Assert ($capture.Compared -eq 3) 'Window edges should not be compared.'
    $wire = @($report.Continuity | Where-Object { $_.From -eq 'WRITE_END' })[0]
    Assert ($wire.MissingOccurrences -eq 1) 'Expected the missing received source PTS.'
    Assert ($wire.MissingSamples[0].PtsUs -eq 1000000) 'Missing source PTS changed.'
    $encoded = @($report.Continuity | Where-Object { $_.From -eq 'ENCODED' })[0]
    Assert ($encoded.MissingOccurrences -eq 0) 'Recovery generation remapping must not imply sender packet loss.'
    $writeDuration = @($report.Durations | Where-Object { $_.Begin -eq 'WRITE_BEGIN' })[0]
    Assert ($writeDuration.Matched -eq 3 -and $writeDuration.MaximumMs -eq 2) 'Local write durations must use matched timestamps.'
    Assert ($report.MinuteCounts.Count -eq 1 -and $report.MinuteCounts[0].Captured -eq 5) 'Per-minute capture counts must preserve duplicate PTS.'
    Assert ($report.MinuteCounts[0].Encoded -eq 3 -and $report.MinuteCounts[0].Received -eq 2) 'Per-minute stage counts must remain independent.'
    $noReceiver = & $analyzer -SenderTrace $sender | ConvertFrom-Json
    Assert ($noReceiver.Continuity.Count -eq 2) 'Sender-only report should work before receiver collection.'
    Write-Trace $receiver 78 $received
    $rejected = $false
    try { & $analyzer -SenderTrace $sender -ReceiverTrace $receiver | Out-Null }
    catch { $rejected = $_.Exception.Message -like '*session IDs differ*' }
    Assert $rejected 'Different sessions must be rejected.'
    Write-Trace $sender 77 $events 1
    $rejected = $false
    try { & $analyzer -SenderTrace $sender | Out-Null }
    catch { $rejected = $_.Exception.Message -like '*Overflowed trace*' }
    Assert $rejected 'Overflow must not look like proven continuity.'
    Write-Trace $sender 77 @()
    $empty = & $analyzer -SenderTrace $sender | ConvertFrom-Json
    Assert ($empty.Continuity[0].Status -eq 'insufficient_events') 'Empty stages must not imply zero frame loss.'
    $longEvents = @(Event 'SUBMIT_END' 0; Event 'ENCODED' 0; Event 'SUBMIT_END' 60000000; Event 'ENCODED' 60000000)
    Write-Trace $sender 77 $longEvents
    $minutes = & $analyzer -SenderTrace $sender | ConvertFrom-Json
    Assert ($minutes.MinuteCounts.Count -eq 2 -and $minutes.MinuteCounts[1].Minute -eq 2) 'Source-PTS minute boundaries must be exact.'
    'Video trace analyzer passed: multiset matching, boundary exclusion, generation remapping, sender-only, session mismatch, overflow and empty stages.'
} finally {
    foreach ($path in @($sender, $receiver)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    $directory.Delete()
}
