param(
    [Parameter(Mandatory = $true)][string]$SenderTrace,
    [string]$ReceiverTrace,
    [long]$BoundaryMarginUs = 500000
)
$ErrorActionPreference = 'Stop'
if ($BoundaryMarginUs -lt 0) { throw 'BoundaryMarginUs cannot be negative.' }

function Read-Trace([string]$Path) {
    $rows = @(Get-Content -LiteralPath $Path | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $headers = @($rows | Where-Object { $_.kind -eq 'header' })
    $summaries = @($rows | Where-Object { $_.kind -eq 'summary' })
    if ($headers.Count -ne 1 -or $summaries.Count -ne 1) { throw "Expected one header and summary in $Path" }
    $events = @($rows | Where-Object { $_.kind -eq 'event' })
    foreach ($event in $events) {
        if ($null -eq $event.atNs -or $null -eq $event.ptsUs -or $null -eq $event.generation -or
            [string]::IsNullOrWhiteSpace($event.stage)) { throw "Incomplete timestamp event in $Path" }
    }
    if ($summaries[0].events -ne $events.Count) { throw "Event count mismatch in $Path" }
    if ($summaries[0].dropped -ne 0) { throw "Overflowed trace cannot prove frame continuity: $Path" }
    if ($headers[0].frequency -le 0) { throw "Missing monotonic clock frequency in $Path" }
    [pscustomobject]@{ Header = $headers[0]; Summary = $summaries[0]; Events = $events }
}

function Get-Stage($Trace, [string]$Stage) {
    @($Trace.Events | Where-Object { $_.stage -eq $Stage -and $_.ptsUs -ge 0 })
}

function Get-Cadence($Trace, [string]$Stage) {
    $events = @(Get-Stage $Trace $Stage)
    $intervals = @(for ($i = 1; $i -lt $events.Count; $i++) {
        ([double]$events[$i].ptsUs - [double]$events[$i - 1].ptsUs) / 1000
    })
    $sorted = @($intervals | Sort-Object)
    $observed = @(for ($i = 1; $i -lt $events.Count; $i++) {
        ([double]$events[$i].atNs - [double]$events[$i - 1].atNs) / 1000000
    })
    $observed = @($observed | Sort-Object)
    [pscustomobject]@{
        Stage = $Stage; Count = $events.Count
        MedianSourceIntervalMs = $(if ($sorted.Count) { $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.5)] } else { $null })
        P95SourceIntervalMs = $(if ($sorted.Count) { $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.95)] } else { $null })
        MaximumSourceIntervalMs = $(if ($sorted.Count) { $sorted[-1] } else { $null })
        P95ObservedIntervalMs = $(if ($observed.Count) { $observed[[int][math]::Floor(($observed.Count - 1) * 0.95)] } else { $null })
        MaximumObservedIntervalMs = $(if ($observed.Count) { $observed[-1] } else { $null })
        Regressions = @($intervals | Where-Object { $_ -lt 0 }).Count
    }
}

function Get-Durations($Trace, [string]$Begin, [string]$End) {
    $pending = @{}
    $durations = @()
    foreach ($event in $Trace.Events) {
        if ($event.stage -ne $Begin -and $event.stage -ne $End) { continue }
        $key = "$($event.ptsUs):$($event.generation)"
        if ($event.stage -eq $Begin) {
            if (-not $pending.ContainsKey($key)) { $pending[$key] = New-Object 'System.Collections.Generic.Queue[long]' }
            $pending[$key].Enqueue([long]$event.atNs)
        } elseif ($pending.ContainsKey($key) -and $pending[$key].Count -gt 0) {
            $durations += ([double]$event.atNs - [double]$pending[$key].Dequeue()) / 1000000
        }
    }
    $sorted = @($durations | Sort-Object)
    [pscustomobject]@{
        Begin = $Begin; End = $End; Matched = $sorted.Count
        P95Ms = $(if ($sorted.Count) { $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.95)] } else { $null })
        MaximumMs = $(if ($sorted.Count) { $sorted[-1] } else { $null })
    }
}

function Compare-Stages($FromTrace, [string]$FromStage, $ToTrace, [string]$ToStage, [bool]$MatchGeneration) {
    $from = @(Get-Stage $FromTrace $FromStage)
    $to = @(Get-Stage $ToTrace $ToStage)
    if ($from.Count -eq 0 -or $to.Count -eq 0) {
        return [pscustomobject]@{ From = $FromStage; To = $ToStage; Status = 'insufficient_events' }
    }
    $lower = [math]::Max(($from | Measure-Object ptsUs -Minimum).Minimum, ($to | Measure-Object ptsUs -Minimum).Minimum) + $BoundaryMarginUs
    $upper = [math]::Min(($from | Measure-Object ptsUs -Maximum).Maximum, ($to | Measure-Object ptsUs -Maximum).Maximum) - $BoundaryMarginUs
    if ($lower -gt $upper) {
        return [pscustomobject]@{ From = $FromStage; To = $ToStage; Status = 'no_interior_overlap' }
    }
    $remaining = @{}
    foreach ($event in $to) {
        if ($event.ptsUs -lt $lower -or $event.ptsUs -gt $upper) { continue }
        $key = "$($event.ptsUs)"
        if ($MatchGeneration) { $key += ":$($event.generation)" }
        if (-not $remaining.ContainsKey($key)) { $remaining[$key] = 0 }
        $remaining[$key]++
    }
    $missing = @()
    $compared = 0
    foreach ($event in $from) {
        if ($event.ptsUs -lt $lower -or $event.ptsUs -gt $upper) { continue }
        $compared++
        $key = "$($event.ptsUs)"
        if ($MatchGeneration) { $key += ":$($event.generation)" }
        if ($remaining.ContainsKey($key) -and $remaining[$key] -gt 0) { $remaining[$key]-- }
        else { $missing += [pscustomobject]@{ PtsUs = $event.ptsUs; Generation = $event.generation } }
    }
    [pscustomobject]@{
        From = $FromStage; To = $ToStage; Status = 'measured'
        LowerPtsUs = $lower; UpperPtsUs = $upper; Compared = $compared
        MissingOccurrences = $missing.Count; MissingSamples = @($missing | Select-Object -First 20)
    }
}

$sender = Read-Trace $SenderTrace
$cadence = @('CAPTURE_ACQUIRED', 'CAPTURE_FILTERED', 'SUBMIT_END', 'ENCODED', 'WRITE_END' |
    ForEach-Object { Get-Cadence $sender $_ })
$comparisons = @(
    Compare-Stages $sender 'SUBMIT_END' $sender 'ENCODED' $false
    Compare-Stages $sender 'ENCODED' $sender 'WRITE_END' $false
)
$minuteEvents = @($sender.Events | Where-Object { $_.ptsUs -ge 0 -and
    $_.stage -in @('SUBMIT_END', 'ENCODED', 'WRITE_END') })
if ($ReceiverTrace) {
    $receiver = Read-Trace $ReceiverTrace
    if ($sender.Header.session -ne $receiver.Header.session) { throw 'Sender and receiver session IDs differ.' }
    $comparisons += Compare-Stages $sender 'WRITE_END' $receiver 'RECEIVED' $true
    $comparisons += Compare-Stages $receiver 'RECEIVED' $receiver 'DECODED' $true
    $comparisons += Compare-Stages $receiver 'DECODED' $receiver 'PRESENTED' $true
    $cadence += @('RECEIVED', 'DECODED', 'PRESENTED' | ForEach-Object { Get-Cadence $receiver $_ })
    $minuteEvents += @($receiver.Events | Where-Object { $_.ptsUs -ge 0 -and
        $_.stage -in @('RECEIVED', 'DECODED', 'PRESENTED', 'SKIPPED') })
}
$minuteCounts = @()
if ($minuteEvents.Count) {
    $firstPts = ($minuteEvents | Measure-Object ptsUs -Minimum).Minimum
    $minuteCounts = @($minuteEvents | Group-Object { [math]::Floor(($_.ptsUs - $firstPts) / 60000000) } |
        Sort-Object { [int]$_.Name } | ForEach-Object {
            $counts = @{}
            foreach ($event in $_.Group) {
                if (-not $counts.ContainsKey($event.stage)) { $counts[$event.stage] = 0 }
                $counts[$event.stage]++
            }
            [pscustomobject]@{
                Minute = [int]$_.Name + 1
                Captured = [int]$counts['SUBMIT_END']; Encoded = [int]$counts['ENCODED']
                Written = [int]$counts['WRITE_END']; Received = [int]$counts['RECEIVED']
                Decoded = [int]$counts['DECODED']; Presented = [int]$counts['PRESENTED']
                Skipped = [int]$counts['SKIPPED']
            }
        })
}
[pscustomobject]@{
    Session = $sender.Header.session
    SenderWindowComplete = $sender.Summary.windowComplete
    ReceiverWindowComplete = $(if ($ReceiverTrace) { $receiver.Summary.windowComplete } else { $null })
    BoundaryMarginUs = $BoundaryMarginUs
    Cadence = $cadence
    Durations = @(
        Get-Durations $sender 'SUBMIT_BEGIN' 'SUBMIT_END'
        Get-Durations $sender 'WRITE_BEGIN' 'WRITE_END'
    )
    Continuity = $comparisons
    MinuteCounts = $minuteCounts
    Caveats = @(
        'Missing occurrences identify stage discontinuities, not automatically a bug: FPS filtering, recovery and latest-frame replacement may be intentional.',
        'Captured-image gaps do not prove source-video loss; static images and variable-rate video are valid.',
        'Encoder repeats need not match a newly captured image. PTS are compared as multisets, not assumed 60 FPS.',
        'Only interior overlapping PTS are compared. Device-local monotonic clocks are not synchronized.'
        'Per-minute stage counts use shared source-PTS bins, not matched interior windows; first/last bins may be partial.'
    )
} | ConvertTo-Json -Depth 8
