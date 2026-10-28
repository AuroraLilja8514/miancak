# SPDX-License-Identifier: GPL-3.0-only
param([Parameter(Mandatory=$true)][string]$OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent $PSScriptRoot
. (Join-Path $source 'Miancak-Keygen.Core.ps1')
$root = Join-Path $env:RUNNER_TEMP ('miancak-keygen-test-' + [Guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($root)
$null = [IO.Directory]::CreateDirectory($OutputDirectory)
$checks = [Collections.Generic.List[string]]::new()
function Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw ('CHECK FAILED: ' + $Name) }
    $checks.Add($Name)
    Write-Output ('PASS: ' + $Name)
}
$fixtureText = 'MIANCAK_FIXTURE_ONLY_8Jt#4xeF!73q'
$secret = ConvertTo-SecureString -String $fixtureText -AsPlainText -Force
$wrong = ConvertTo-SecureString -String 'WRONG_FIXTURE_PASSWORD_123456789' -AsPlainText -Force
$short = ConvertTo-SecureString -String 'short' -AsPlainText -Force
$entry = $null
try {
    # Parse production files using Windows PowerShell 5.1, not just a YAML/text check.
    foreach ($file in Get-ChildItem -LiteralPath $source -Filter '*.ps1') {
        $tokens = $null; $errors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
        Check ($errors.Count -eq 0) ('syntax-' + $file.Name)
    }
    $storesBefore = @(Get-ChildItem Cert:\CurrentUser\My | ForEach-Object { $_.Thumbprint } | Sort-Object)
    $name = ([char]0x6D4B).ToString() + ([char]0x8BD5).ToString() + ' folder with spaces'
    $parent = Join-Path $root $name
    $null = [IO.Directory]::CreateDirectory($parent)
    $created = @(New-MiancakKeyFiles -ParentDirectory $parent -Password $secret)
    Check ($created.Count -eq 1) 'one-result-no-accidental-pipeline-output'
    $result = $created[0]
    $expected = @('miancak-release.p12', 'miancak-release-cert.pem', 'certificate-info.json', 'README-output.txt', 'SHA256SUMS.txt') | Sort-Object
    $actual = @(Get-ChildItem -LiteralPath $result.Directory -File | Select-Object -ExpandProperty Name | Sort-Object)
    Check (($actual -join '|') -eq ($expected -join '|')) 'complete-expected-files-only'
    $p12 = Join-Path $result.Directory 'miancak-release.p12'
    $pfxHex = ([BitConverter]::ToString([IO.File]::ReadAllBytes($p12))).Replace('-', '')
    Check ($pfxHex.Contains('06092A864886F70D01050D') -and $pfxHex.Contains('060960864801650304012A')) 'actual-pfx-pbes2-aes256-oid'
    $info = Get-Content -LiteralPath (Join-Path $result.Directory 'certificate-info.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Check ($info.alias -eq 'miancak-release' -and $info.applicationId -eq 'org.miancak.keyboard') 'alias-and-project-metadata'
    Check ($info.privateKeyEncryption -eq 'AES256_SHA256' -and -not $info.passwordSaved) 'encrypted-export-policy'
    Check ($info.keySize -eq 3072 -and $info.certificateSignatureAlgorithm -eq 'SHA256withRSA') 'rsa3072-sha256'
    Check (([DateTime]$info.notAfterUtc - [DateTime]$info.notBeforeUtc).TotalDays -ge 10950) 'thirty-year-validity'
    Check ($info.certificateSha256 -eq $result.CertificateSha256 -and $info.certificateSha256.Length -eq 64) 'public-fingerprint-matches'
    $flags = [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
    $entry = [Security.Cryptography.X509Certificates.X509Certificate2]::new($p12, $secret, $flags)
    Check ($entry.HasPrivateKey -and $entry.FriendlyName -eq 'miancak-release') 'pkcs12-private-key-roundtrip'
    $rejected = $false
    try { $unexpected = [Security.Cryptography.X509Certificates.X509Certificate2]::new($p12, $wrong, $flags); $unexpected.Dispose() }
    catch { $rejected = $true }
    Check $rejected 'wrong-password-rejected'
    foreach ($file in Get-ChildItem -LiteralPath $result.Directory -File | Where-Object { $_.Extension -ne '.p12' }) {
        Check (-not ([IO.File]::ReadAllText($file.FullName).Contains($fixtureText))) ('password-not-written-' + $file.Name)
    }
    $originalHash = Get-MiancakSha256 -Bytes ([IO.File]::ReadAllBytes($p12))
    $rejected = $false
    try { $null = New-MiancakKeyFiles -ParentDirectory $parent -Password $secret }
    catch { $rejected = $_.Exception.Message.Contains('already exists') }
    Check ($rejected -and (Get-MiancakSha256 -Bytes ([IO.File]::ReadAllBytes($p12))) -eq $originalHash) 'existing-keys-not-overwritten'
    $invalidParent = Join-Path $root 'invalid-password'
    $null = [IO.Directory]::CreateDirectory($invalidParent)
    $rejected = $false
    try { $null = New-MiancakKeyFiles -ParentDirectory $invalidParent -Password $short }
    catch { $rejected = $true }
    Check ($rejected -and @(Get-ChildItem -LiteralPath $invalidParent -Force).Count -eq 0) 'invalid-password-no-output'
    $rejected = $false
    try { $null = New-MiancakKeyFiles -ParentDirectory (Join-Path $root 'not-created') -Password $secret }
    catch { $rejected = $true }
    Check ($rejected -and -not (Test-Path -LiteralPath (Join-Path $root 'not-created'))) 'missing-parent-no-output'
    Check (@(Get-ChildItem -LiteralPath $parent -Force -Filter '.miancak-keygen-*').Count -eq 0) 'staging-directory-cleaned'
    $acl = Get-Acl -LiteralPath $result.Directory
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $allowed = @($sid, 'S-1-5-18')
    Check $acl.AreAccessRulesProtected 'output-acl-not-inherited'
    $otherRules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) | Where-Object { $_.IdentityReference.Value -notin $allowed })
    Check ($otherRules.Count -eq 0) 'output-acl-current-user-and-system-only'
    $env:MIANCAK_FIXTURE_PASSWORD = $fixtureText
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    & $java (Join-Path $PSScriptRoot 'KeyStoreInterop.java') $p12 (Join-Path $result.Directory 'miancak-release-cert.pem')
    Check ($LASTEXITCODE -eq 0) 'java17-pkcs12-privatekey-and-signature-interop'
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    $keyInfo = & $keytool -list -storetype PKCS12 -keystore $p12 -storepass:env MIANCAK_FIXTURE_PASSWORD 2>&1
    Check ($LASTEXITCODE -eq 0 -and ($keyInfo -join "`n").Contains('miancak-release')) 'java17-keytool-alias-interop'
    Remove-Item Env:\MIANCAK_FIXTURE_PASSWORD
    $storesAfter = @(Get-ChildItem Cert:\CurrentUser\My | ForEach-Object { $_.Thumbprint } | Sort-Object)
    Check (($storesBefore -join '|') -eq ($storesAfter -join '|')) 'windows-certificate-store-unchanged'
    & (Join-Path $source 'Miancak-Keygen.ps1') -SmokeTestUi -ScreenshotPath (Join-Path $OutputDirectory 'ui-preview.png')
    Check ($LASTEXITCODE -eq 0) 'windows-forms-ui-initial-state'
    [ordered]@{ status='passed'; checks=$checks.Count; passed=@($checks); platform=([Environment]::OSVersion.VersionString + ' / Windows PowerShell ' + $PSVersionTable.PSVersion.ToString()); javaInterop='Temurin 17'; realUserKeysUsed=$false } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'test-report.json') -Encoding UTF8
}
finally {
    if ($null -ne $entry) { $entry.Dispose() }
    foreach ($value in @($secret, $wrong, $short)) { $value.Dispose() }
    Remove-Item Env:\MIANCAK_FIXTURE_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
