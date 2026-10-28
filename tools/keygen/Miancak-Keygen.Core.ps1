# SPDX-License-Identifier: GPL-3.0-only
Set-StrictMode -Version Latest

function Assert-MiancakEnvironment {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'This generator requires Windows 10/11 with Windows PowerShell 5.1.'
    }
    if ($PSVersionTable.PSEdition -ne 'Desktop' -or $PSVersionTable.PSVersion.Major -lt 5) {
        throw 'Please use the included Start-Keygen.cmd (Windows PowerShell 5.1).'
    }
    try { $null = [System.Security.Cryptography.X509Certificates.CertificateRequest] }
    catch { throw 'Windows .NET Framework 4.7.2 or newer is required. No files were generated.' }
    if (-not ('Miancak.NativePfx' -as [type])) {
        Add-Type -Path (Join-Path $PSScriptRoot 'Miancak-NativePfx.cs') -ErrorAction Stop
    }
}

function Assert-MiancakPassword {
    param([Parameter(Mandatory=$true)][Security.SecureString]$Password)
    if ($Password.Length -lt 20 -or $Password.Length -gt 128) {
        throw 'Use a password containing 20 to 128 printable ASCII characters.'
    }
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
        for ($i = 0; $i -lt $Password.Length; $i++) {
            $c = [Runtime.InteropServices.Marshal]::ReadInt16($pointer, $i * 2)
            if ($c -lt 33 -or $c -gt 126) {
                throw 'Use English letters, digits or punctuation; spaces/control characters are not supported.'
            }
        }
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}

function Get-MiancakSha256 {
    param([Parameter(Mandatory=$true)][byte[]]$Bytes)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}

function Set-MiancakPrivateDirectory {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $propagation = [Security.AccessControl.PropagationFlags]::None
    $allow = [Security.AccessControl.AccessControlType]::Allow
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', $inherit, $propagation, $allow))
    $systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($systemSid, 'FullControl', $inherit, $propagation, $allow))
    Set-Acl -LiteralPath $Path -AclObject $acl -ErrorAction Stop
}

function New-MiancakKeyFiles {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$ParentDirectory,
        [Parameter(Mandatory=$true)][Security.SecureString]$Password
    )
    Assert-MiancakEnvironment
    Assert-MiancakPassword -Password $Password
    $parent = Get-Item -LiteralPath $ParentDirectory -ErrorAction Stop
    if (-not $parent.PSIsContainer -or $parent.PSProvider.Name -ne 'FileSystem') {
        throw 'Select an existing filesystem directory.'
    }
    $final = Join-Path $parent.FullName 'MIANCAK-keys'
    if (Test-Path -LiteralPath $final) {
        throw 'MIANCAK-keys already exists. Existing keys will never be overwritten. Keep using the original key.'
    }
    $stage = Join-Path $parent.FullName ('.miancak-keygen-' + [Guid]::NewGuid().ToString('N'))
    $rsa = $null
    $certificate = $null
    $loaded = $null
    $loadedKey = $null
    $publicKey = $null
    $finished = $false
    try {
        $null = [IO.Directory]::CreateDirectory($stage)
        Set-MiancakPrivateDirectory -Path $stage
        $rsa = [Security.Cryptography.RSACng]::new(3072)
        $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
            'CN=MIANCAK', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $null = $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
        $usage = [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature
        $null = $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($usage, $true))
        $start = [DateTimeOffset]::UtcNow.AddMinutes(-5)
        $certificate = $request.CreateSelfSigned($start, $start.AddYears(30))
        $certificate.FriendlyName = 'miancak-release'
        $p12 = Join-Path $stage 'miancak-release.p12'
        $encrypted = [Miancak.NativePfx]::Export($certificate, $Password)
        [IO.File]::WriteAllBytes($p12, $encrypted)

        # Reload only in memory and prove that the password, private key and public certificate agree.
        $flags = [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
        $loaded = [Security.Cryptography.X509Certificates.X509Certificate2]::new($p12, $Password, $flags)
        if (-not $loaded.HasPrivateKey) {
            throw 'The exported PKCS12 private key could not be verified.'
        }
        $certificateSha256 = Get-MiancakSha256 -Bytes $certificate.RawData
        if ((Get-MiancakSha256 -Bytes $loaded.RawData) -ne $certificateSha256) {
            throw 'Certificate verification failed.'
        }
        $loadedKey = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($loaded)
        $publicKey = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($certificate)
        $probe = [Text.Encoding]::UTF8.GetBytes('MIANCAK local key verification v1')
        $signature = $loadedKey.SignData($probe, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        if (-not $publicKey.VerifyData($probe, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) {
            throw 'The private key did not pass the signing verification.'
        }
        $base64 = [Convert]::ToBase64String($certificate.RawData, [Base64FormattingOptions]::InsertLineBreaks)
        $pem = "-----BEGIN CERTIFICATE-----`r`n" + $base64 + "`r`n-----END CERTIFICATE-----`r`n"
        $utf8 = [Text.UTF8Encoding]::new($false)
        [IO.File]::WriteAllText((Join-Path $stage 'miancak-release-cert.pem'), $pem, $utf8)
        $info = [ordered]@{
            formatVersion = 1
            applicationId = 'org.miancak.keyboard'
            purpose = 'MIANCAK release signing only; this label is not cryptographic access control'
            alias = 'miancak-release'
            subject = $certificate.Subject
            issuer = $certificate.Issuer
            keyAlgorithm = 'RSA'
            keySize = 3072
            certificateSignatureAlgorithm = 'SHA256withRSA'
            notBeforeUtc = $certificate.NotBefore.ToUniversalTime().ToString('o')
            notAfterUtc = $certificate.NotAfter.ToUniversalTime().ToString('o')
            certificateSha256 = $certificateSha256
            keystoreFormat = 'PKCS12'
            privateKeyEncryption = 'AES256_SHA256'
            passwordSaved = $false
            generatorVersion = '1.0.0'
        }
        [IO.File]::WriteAllText((Join-Path $stage 'certificate-info.json'), ($info | ConvertTo-Json), $utf8)
        $instructions = @'
MIANCAK signing key files

miancak-release.p12 contains the encrypted private key. Keep at least two encrypted backups.
miancak-release-cert.pem and certificate-info.json contain public information and may be shared.
The password was NOT saved. Store it separately in your password manager.
Do not regenerate this key to sign later versions. A new key cannot replace this signing identity.
Intended application: org.miancak.keyboard. This is a usage rule, not a cryptographic restriction.
No certificate was installed into the Windows certificate store. No administrator access was requested.
The generator has no network operations or automatic upload.
SHA256SUMS.txt hashes the generated files; it does not contain the password.
'@
        [IO.File]::WriteAllText((Join-Path $stage 'README-output.txt'), $instructions, $utf8)
        $hashLines = foreach ($name in @('miancak-release.p12', 'miancak-release-cert.pem', 'certificate-info.json', 'README-output.txt')) {
            (Get-MiancakSha256 -Bytes ([IO.File]::ReadAllBytes((Join-Path $stage $name)))) + '  ' + $name
        }
        [IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), ($hashLines -join "`r`n") + "`r`n", $utf8)
        # Directory.Move refuses to replace a destination created by another concurrent process.
        [IO.Directory]::Move($stage, $final)
        $finished = $true
        return [pscustomobject]@{ Directory = $final; CertificateSha256 = $certificateSha256; Alias = 'miancak-release' }
    }
    finally {
        foreach ($item in @($loadedKey, $publicKey, $loaded, $certificate, $rsa)) {
            if ($null -ne $item) { $item.Dispose() }
        }
        if (-not $finished -and (Test-Path -LiteralPath $stage)) {
            Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
