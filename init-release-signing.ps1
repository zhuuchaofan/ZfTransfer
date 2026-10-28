param(
    [string]$Studio = 'C:\Program Files\Android\Android Studio',
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '.release-signing')
)
$ErrorActionPreference = 'Stop'
$key = Join-Path $SigningDirectory 'release.jks'
$passwordFile = Join-Path $SigningDirectory 'password.txt'
if ((Test-Path -LiteralPath $key) -or (Test-Path -LiteralPath $passwordFile)) {
    throw '签名文件已存在，不覆盖。请备份并继续使用原发布密钥。'
}
New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $SigningDirectory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw '无法限制签名目录权限，已停止。' }
$random = New-Object byte[] 48
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($random) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($random)
[IO.File]::WriteAllText($passwordFile, $password, [Text.UTF8Encoding]::new($false))
$previous = $env:ZF_RELEASE_STORE_PASS
try {
    $env:ZF_RELEASE_STORE_PASS = $password
    & "$Studio\jbr\bin\keytool.exe" -genkeypair -keystore $key -storetype JKS -alias zftransfer -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=ZfTransfer, O=ZfTransfer' -storepass:env ZF_RELEASE_STORE_PASS -keypass:env ZF_RELEASE_STORE_PASS
    if ($LASTEXITCODE -ne 0) { throw '生成发布签名失败；请检查本机签名目录，避免覆盖已有密钥。' }
} finally { $env:ZF_RELEASE_STORE_PASS = $previous; $password = $null }
Write-Host '发布签名已创建。请将整个本机签名目录安全备份；不要上传密钥或口令。'
