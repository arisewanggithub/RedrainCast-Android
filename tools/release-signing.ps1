param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [switch]$ConfigureSecrets
)
$ErrorActionPreference = 'Stop'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (!(Test-Path -LiteralPath $keytool)) { throw 'Set JAVA_HOME to JDK 21.' }
$Directory = [System.IO.Path]::GetFullPath($Directory)
$repository = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Directory.TrimEnd('\') -eq $repository.TrimEnd('\') -or
    $Directory.StartsWith($repository.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing storage must be outside the application repository.'
}
$null = New-Item -ItemType Directory -Force -Path $Directory
$Directory = (Resolve-Path -LiteralPath $Directory).Path
$key = Join-Path $Directory 'redraincast-release.jks'
$credentials = Join-Path $Directory 'redraincast-release.credentials.json'

function Protect-File([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { $null = $acl.RemoveAccessRuleSpecific($rule) }
    foreach ($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User,
                       [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid, 'FullControl', 'Allow'))
    }
    [System.IO.File]::SetAccessControl($Path, $acl)
}
function Run-Quiet([string]$Exe, [string]$Arguments, [string]$InputText = '') {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Exe
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($InputText)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $null = $stdout.GetAwaiter().GetResult()
    $null = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Credential operation failed (exit $($process.ExitCode)); no secret output was logged." }
}
function New-Password {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); [Convert]::ToBase64String($bytes) }
    finally { $rng.Dispose() }
}
if ((Test-Path -LiteralPath $key) -ne (Test-Path -LiteralPath $credentials)) {
    throw 'Partial existing signing storage: restore the matching key/credentials; nothing overwritten.'
}
$staging = Join-Path $Directory ('redraincast-signing-' + [Guid]::NewGuid().ToString('N'))
try {
    if (!(Test-Path -LiteralPath $key)) {
        $null = New-Item -ItemType Directory -Path $staging
        $acl = New-Object System.Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User,
                           [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
            $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
                $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        }
        Set-Acl -LiteralPath $staging -AclObject $acl
        $data = @{ alias = 'redraincast'; storePassword = (New-Password); keyPassword = (New-Password) }
        $env:REDRAIN_STORE_PASSWORD = $data.storePassword
        $env:REDRAIN_KEY_PASSWORD = $data.keyPassword
        $stagedKey = Join-Path $staging 'redraincast-release.jks'
        Run-Quiet $keytool "-genkeypair -keystore `"$stagedKey`" -storetype JKS -alias redraincast -keyalg RSA -keysize 3072 -validity 10000 -dname CN=RedrainCast -storepass:env REDRAIN_STORE_PASSWORD -keypass:env REDRAIN_KEY_PASSWORD"
        Protect-File $stagedKey
        $stream = [System.IO.File]::Open($credentials, 'CreateNew', 'Write', 'None')
        $stream.Dispose()
        Protect-File $credentials
        [System.IO.File]::WriteAllText($credentials, ($data | ConvertTo-Json))
        Move-Item -LiteralPath $stagedKey -Destination $key
    }
    Protect-File $key
    Protect-File $credentials
    $data = Get-Content -LiteralPath $credentials -Raw | ConvertFrom-Json
    if ($data.alias -ne 'redraincast' -or !$data.storePassword -or !$data.keyPassword) {
        throw 'Invalid existing signing credentials; nothing overwritten.'
    }
    $env:REDRAIN_STORE_PASSWORD = $data.storePassword
    $env:REDRAIN_KEY_PASSWORD = $data.keyPassword
    $cert = Join-Path $Directory ('redraincast-cert-' + [Guid]::NewGuid().ToString('N') + '.der')
    Run-Quiet $keytool "-exportcert -keystore `"$key`" -alias redraincast -storepass:env REDRAIN_STORE_PASSWORD -file `"$cert`""
    $fingerprint = (Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash
    if ($ConfigureSecrets) {
        $gh = (Get-Command gh -ErrorAction Stop).Source
        $secrets = @{
            ANDROID_RELEASE_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($key))
            ANDROID_RELEASE_STORE_PASSWORD = $data.storePassword
            ANDROID_RELEASE_KEY_PASSWORD = $data.keyPassword
            ANDROID_RELEASE_KEY_ALIAS = $data.alias
        }
        foreach ($name in $secrets.Keys) {
            Run-Quiet $gh "secret set $name --repo arisewanggithub/RedrainCast-Android" $secrets[$name]
            Write-Output "Configured $name"
        }
    }
    Write-Output "Production certificate SHA-256: $fingerprint"
} finally {
    Remove-Item Env:\REDRAIN_STORE_PASSWORD, Env:\REDRAIN_KEY_PASSWORD -ErrorAction SilentlyContinue
    if ($cert -and (Test-Path -LiteralPath $cert)) { Remove-Item -LiteralPath $cert }
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging }
}
