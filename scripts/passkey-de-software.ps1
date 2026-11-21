<#
.SYNOPSIS
  Um "aparelho" de passkey em PowerShell, so para o roteiro de fumaca (smoke-http.ps1).

.DESCRIPTION
  Faz o papel do Windows Hello / celular sem navegador: chave EC P-256 (ES256), authenticatorData,
  attestationObject em CBOR e assinatura ECDSA, como a especificacao WebAuthn manda. E o mesmo
  principio do AutenticadorDeTeste.java dos testes, para o roteiro conseguir entrar na API de
  verdade.

  ATENCAO: a chave privada de uma passkey de software fica onde o script a guardar (o smoke guarda
  no Vault). Isso so serve para uma conta de TESTE do ambiente de desenvolvimento. Pessoas de
  verdade usam passkeys de verdade, cuja chave nunca sai do aparelho.

  Este arquivo e carregado com ". .\scripts\passkey-de-software.ps1" (dot-source).
#>

function ConvertTo-B64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function ConvertFrom-B64Url([string]$texto) {
    $s = $texto.Replace('-', '+').Replace('_', '/')
    while ($s.Length % 4) { $s += '=' }
    # A virgula (aqui e abaixo) devolve o byte[] inteiro: sem ela o PowerShell o desenrola num Object[].
    , [Convert]::FromBase64String($s)
}

function Get-Sha256([byte[]]$bytes) {
    $h = [System.Security.Cryptography.SHA256]::Create()
    try { , $h.ComputeHash($bytes) } finally { $h.Dispose() }
}

# ---- CBOR minimo (inteiros, bytes, texto, mapas) ---------------------------------------------

function Add-CborCabecalho([System.Collections.Generic.List[byte]]$saida, [int]$tipo, [long]$n) {
    if ($n -lt 24) { $saida.Add([byte](($tipo -shl 5) -bor $n)) }
    elseif ($n -lt 256) { $saida.Add([byte](($tipo -shl 5) -bor 24)); $saida.Add([byte]$n) }
    elseif ($n -lt 65536) { $saida.Add([byte](($tipo -shl 5) -bor 25)); $saida.Add([byte]($n -shr 8)); $saida.Add([byte]($n -band 0xFF)) }
    else { throw 'CBOR: grande demais' }
}
function Add-CborInteiro($saida, [long]$n) { if ($n -ge 0) { Add-CborCabecalho $saida 0 $n } else { Add-CborCabecalho $saida 1 (-1 - $n) } }
function Add-CborBytes($saida, [byte[]]$b) { Add-CborCabecalho $saida 2 $b.Length; $saida.AddRange($b) }
function Add-CborTexto($saida, [string]$t) { $b = [Text.Encoding]::UTF8.GetBytes($t); Add-CborCabecalho $saida 3 $b.Length; $saida.AddRange($b) }

# ---- assinatura: o .NET devolve r||s (64 bytes); a WebAuthn quer DER --------------------------

function ConvertTo-DerInteiro([byte[]]$valor) {
    $i = 0
    while ($i -lt $valor.Length - 1 -and $valor[$i] -eq 0) { $i++ }
    $v = [byte[]]$valor[$i..($valor.Length - 1)]
    if ($v[0] -band 0x80) { $v = [byte[]](@(0) + $v) }
    [byte[]](@(0x02, $v.Length) + $v)
}
function ConvertTo-Der([byte[]]$p1363) {
    $r = ConvertTo-DerInteiro ([byte[]]$p1363[0..31])
    $s = ConvertTo-DerInteiro ([byte[]]$p1363[32..63])
    [byte[]](@(0x30, ($r.Length + $s.Length)) + $r + $s)
}

# ---- o aparelho ----------------------------------------------------------------------------------

<#
  Passkey nova (chave e id de credencial sorteados). O objeto guarda tudo em base64url, para poder
  ir para o Vault e voltar: d (chave privada), x, y, credencialId, handle, contador.
#>
function New-PasskeyDeSoftware {
    $ec = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve+NamedCurves]::nistP256)
    $p = $ec.ExportParameters($true)
    $id = New-Object byte[] 32
    $g = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $g.GetBytes($id) } finally { $g.Dispose() }
    [pscustomobject]@{ d = (ConvertTo-B64Url $p.D); x = (ConvertTo-B64Url $p.Q.X); y = (ConvertTo-B64Url $p.Q.Y)
        credencialId = (ConvertTo-B64Url $id); handle = $null; contador = 0 }
}

function Get-ChaveDaPasskey($pk) {
    $par = New-Object System.Security.Cryptography.ECParameters
    $par.Curve = [System.Security.Cryptography.ECCurve+NamedCurves]::nistP256
    $q = New-Object System.Security.Cryptography.ECPoint
    $q.X = ConvertFrom-B64Url $pk.x
    $q.Y = ConvertFrom-B64Url $pk.y
    $par.Q = $q
    $par.D = ConvertFrom-B64Url $pk.d
    $ec = [System.Security.Cryptography.ECDsa]::Create()
    $ec.ImportParameters($par)
    $ec
}

function Get-ClientData([string]$tipo, [string]$desafio, [string]$origem) {
    '{"type":"' + $tipo + '","challenge":"' + $desafio + '","origin":"' + $origem + '","crossOrigin":false}'
}

# rpIdHash (32) | flags (1) | contador (4, big-endian)
function Get-CabecalhoDoAuthData([string]$rpId, [int]$flags, [long]$contador) {
    $saida = New-Object 'System.Collections.Generic.List[byte]'
    $saida.AddRange([byte[]](Get-Sha256 ([Text.Encoding]::UTF8.GetBytes($rpId))))
    $saida.Add([byte]$flags)
    foreach ($desloca in 24, 16, 8, 0) { $saida.Add([byte](($contador -shr $desloca) -band 0xFF)) }
    # A virgula impede o PowerShell de desenrolar a lista em bytes soltos ao devolver.
    ,$saida
}

<# Resposta ao navigator.credentials.create() (RegistrationResponseJSON). #>
function Get-RespostaDeCadastro($pk, [string]$desafio, [string]$handle, [string]$rpId = 'localhost', [string]$origem = 'http://localhost:9090') {
    $pk.handle = $handle
    $cose = New-Object 'System.Collections.Generic.List[byte]'
    Add-CborCabecalho $cose 5 5
    Add-CborInteiro $cose 1; Add-CborInteiro $cose 2         # kty: EC2
    Add-CborInteiro $cose 3; Add-CborInteiro $cose -7        # alg: ES256
    Add-CborInteiro $cose -1; Add-CborInteiro $cose 1        # crv: P-256
    Add-CborInteiro $cose -2; Add-CborBytes $cose (ConvertFrom-B64Url $pk.x)
    Add-CborInteiro $cose -3; Add-CborBytes $cose (ConvertFrom-B64Url $pk.y)
    $id = ConvertFrom-B64Url $pk.credencialId

    # UP | UV | AT
    $authData = Get-CabecalhoDoAuthData $rpId 0x45 0
    $authData.AddRange([byte[]]::new(16))                # AAGUID
    $authData.Add([byte]($id.Length -shr 8)); $authData.Add([byte]($id.Length -band 0xFF))
    $authData.AddRange($id)
    $authData.AddRange($cose)

    $att = New-Object 'System.Collections.Generic.List[byte]'
    Add-CborCabecalho $att 5 3
    Add-CborTexto $att 'fmt'; Add-CborTexto $att 'none'
    Add-CborTexto $att 'attStmt'; Add-CborCabecalho $att 5 0
    Add-CborTexto $att 'authData'; Add-CborBytes $att $authData.ToArray()

    $clientData = Get-ClientData 'webauthn.create' $desafio $origem
    '{"id":"' + $pk.credencialId + '","rawId":"' + $pk.credencialId + '","type":"public-key","clientExtensionResults":{},' +
        '"response":{"clientDataJSON":"' + (ConvertTo-B64Url ([Text.Encoding]::UTF8.GetBytes($clientData))) +
        '","attestationObject":"' + (ConvertTo-B64Url $att.ToArray()) + '","transports":["internal"]}}'
}

<# Resposta ao navigator.credentials.get() (AuthenticationResponseJSON), assinada. Soma 1 ao contador. #>
function Get-RespostaDeLogin($pk, [string]$desafio, [string]$rpId = 'localhost', [string]$origem = 'http://localhost:9090') {
    $pk.contador = [long]$pk.contador + 1
    $authData = (Get-CabecalhoDoAuthData $rpId 0x05 $pk.contador).ToArray()   # UP | UV
    $clientData = [Text.Encoding]::UTF8.GetBytes((Get-ClientData 'webauthn.get' $desafio $origem))
    $assinado = [byte[]]($authData + (Get-Sha256 $clientData))
    $ec = Get-ChaveDaPasskey $pk
    try { $p1363 = $ec.SignData($assinado, [System.Security.Cryptography.HashAlgorithmName]::SHA256) } finally { $ec.Dispose() }
    '{"id":"' + $pk.credencialId + '","rawId":"' + $pk.credencialId + '","type":"public-key","clientExtensionResults":{},' +
        '"response":{"clientDataJSON":"' + (ConvertTo-B64Url $clientData) + '","authenticatorData":"' + (ConvertTo-B64Url $authData) +
        '","signature":"' + (ConvertTo-B64Url (ConvertTo-Der $p1363)) + '","userHandle":"' + $pk.handle + '"}}'
}
