# blsnip without the game's chat.
#
# Rewrites one key inside blsnip.properties; the loader re-reads the file every
# poll, so the change lands while the game is running. No chat line, no hotkey,
# no injection channel — nothing the client can swallow.
#
#   snip target 2Dance
#   snip key    2ce32425-....
#   snip queue  on
#   snip join   off
#   snip clear
#   snip show

param(
    [Parameter(Position = 0)][string] $Verb = 'help',
    [Parameter(Position = 1)][string] $Value = ''
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$props = Join-Path $dir 'blsnip.properties'

function Read-Props {
    if (-not (Test-Path -LiteralPath $props)) { return @() }
    return @(Get-Content -LiteralPath $props)
}

# Rewrites the value of one key, keeps every comment and every other line, and
# appends the key when the file does not have it yet. Temp-then-move so the
# loader never reads a half-written file.
function Set-Prop([string] $key, [string] $value) {
    $lines = Read-Props
    $found = $false
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($line in $lines) {
        if ($line -match ("^\s*" + [regex]::Escape($key) + "\s*=")) {
            $found = $true
            $out.Add("$key=$value")
        } else {
            $out.Add($line)
        }
    }
    if (-not $found) { $out.Add("$key=$value") }

    $tmp = "$props.tmp"
    Set-Content -LiteralPath $tmp -Value $out -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $props -Force
}

function Get-Prop([string] $key) {
    foreach ($line in Read-Props) {
        if ($line -match ("^\s*" + [regex]::Escape($key) + "\s*=\s*(.*)$")) {
            return $Matches[1].Trim()
        }
    }
    return ''
}

function Show-Props {
    $target = Get-Prop 'target'
    $apiKey = Get-Prop 'apiKey'
    $join = Get-Prop 'autoJoin'
    $queue = Get-Prop 'autoQueue'
    if ([string]::IsNullOrWhiteSpace($target)) { $target = 'none' }
    if ([string]::IsNullOrWhiteSpace($apiKey)) { $apiKey = 'none' } else { $apiKey = 'set' }
    if ([string]::IsNullOrWhiteSpace($join)) { $join = 'true' }
    if ([string]::IsNullOrWhiteSpace($queue)) { $queue = 'false' }
    Write-Host "target = $target"
    Write-Host "apiKey = $apiKey"
    Write-Host "join   = $join"
    Write-Host "queue  = $queue"
    Write-Host "file   = $props"
}

function Convert-Toggle([string] $raw) {
    switch ($raw.ToLowerInvariant()) {
        'on'    { return 'true' }
        'true'  { return 'true' }
        '1'     { return 'true' }
        'yes'   { return 'true' }
        'off'   { return 'false' }
        'false' { return 'false' }
        '0'     { return 'false' }
        'no'    { return 'false' }
        default { return $null }
    }
}

switch ($Verb.ToLowerInvariant()) {

    'target' {
        if ($Value -notmatch '^[A-Za-z0-9_]{1,16}$') {
            Write-Host "target must be 1-16 letters, digits or _"
            exit 2
        }
        Set-Prop 'target' $Value
        Write-Host "target -> $Value"
    }

    'key' {
        if ($Value -notmatch '^[A-Za-z0-9-]{8,36}$') {
            Write-Host "key looks wrong - copy the whole API key"
            exit 2
        }
        Set-Prop 'apiKey' $Value
        Write-Host "apiKey -> set"
    }

    'join' {
        $toggle = Convert-Toggle $Value
        if ($null -eq $toggle) { Write-Host 'join on|off'; exit 2 }
        Set-Prop 'autoJoin' $toggle
        Write-Host "autoJoin -> $toggle"
    }

    'queue' {
        $toggle = Convert-Toggle $Value
        if ($null -eq $toggle) { Write-Host 'queue on|off'; exit 2 }
        Set-Prop 'autoQueue' $toggle
        Write-Host "autoQueue -> $toggle"
    }

    'clear' {
        Set-Prop 'target' ''
        Write-Host 'target cleared'
    }

    'show' {
        Show-Props
    }

    default {
        Write-Host 'snip target <name>   set the target'
        Write-Host 'snip key <apiKey>    enable status polling'
        Write-Host 'snip join on|off     /p join on sight'
        Write-Host 'snip queue on|off    /play the target mode'
        Write-Host 'snip clear           drop the target'
        Write-Host 'snip show            print the current settings'
    }
}
