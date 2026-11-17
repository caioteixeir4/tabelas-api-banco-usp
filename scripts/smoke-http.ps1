<#
.SYNOPSIS
  Roteiro de fumaca por HTTP real contra a API em execucao.

.DESCRIPTION
  Os testes de integracao rodam dentro de uma transacao que e desfeita, e isso esconde erros que so
  aparecem em producao (como LazyInitializationException fora da transacao). Este roteiro chama a
  API de verdade: cria empresa, filial, pessoas de cada tipo, contatos e enderecos, viola as
  regras de proposito e confere o status de cada resposta. No fim apaga os cadastros que criou (toda
  empresa criada tem a marca da rodada na razao social) e confere que nada sobrou.

  A API precisa estar no ar (mvn spring-boot:run). Sai com codigo 1 se alguma verificacao falhar.

.PARAMETER Base
  URL base da API. Padrao: http://localhost:8080

.EXAMPLE
  $env:PORT = 18080; mvn spring-boot:run      # em outro terminal
  .\scripts\smoke-http.ps1 -Base http://localhost:18080
#>
param([string]$Base = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$base = $Base.TrimEnd('/')
Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(30)
$script:ok = 0
$script:falhas = New-Object System.Collections.Generic.List[string]

function Chamar([string]$metodo, [string]$caminho, [string]$corpo = $null, [byte[]]$bytes = $null) {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($metodo), "$base$caminho")
    if ($null -ne $bytes -and $bytes.Length -gt 0) {
        $req.Content = [System.Net.Http.ByteArrayContent]::new($bytes)
    } elseif (-not [string]::IsNullOrEmpty($corpo)) {
        $req.Content = [System.Net.Http.ByteArrayContent]::new([System.Text.Encoding]::UTF8.GetBytes($corpo))
    }
    if ($null -ne $req.Content) {
        $req.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
    }
    $resp = $http.SendAsync($req).GetAwaiter().GetResult()
    $cru = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $texto = [System.Text.Encoding]::UTF8.GetString($cru)
    $json = $null
    if ($texto.Length -gt 0) { try { $json = $texto | ConvertFrom-Json } catch { $json = $null } }
    $loc = $null
    if ($null -ne $resp.Headers.Location) { $loc = $resp.Headers.Location.ToString() }
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $json; Texto = $texto; Location = $loc; Tipo = "$($resp.Content.Headers.ContentType)" }
}

function Confere([string]$nome, [bool]$condicao, [string]$detalhe = '') {
    if ($condicao) { $script:ok++; Write-Host "  ok    $nome" }
    else { $script:falhas.Add($nome); Write-Host "  FALHA $nome  $detalhe" }
}

function Digito([string]$digitos, [int[]]$pesos) {
    $soma = 0
    for ($i = 0; $i -lt $pesos.Count; $i++) { $soma += [int]$digitos.Substring($i, 1) * $pesos[$i] }
    $r = $soma % 11
    if ($r -lt 2) { return 0 } else { return (11 - $r) }
}
function NovaRaiz { -join (1..8 | ForEach-Object { Get-Random -Maximum 10 }) }
function NovoCnpj([string]$raiz, [string]$ordem) {
    $b = "$raiz$ordem"
    $d1 = Digito $b @(5,4,3,2,9,8,7,6,5,4,3,2)
    $d2 = Digito "$b$d1" @(6,5,4,3,2,9,8,7,6,5,4,3,2)
    return "$b$d1$d2"
}
function NovoCpf {
    do { $b = -join (1..9 | ForEach-Object { Get-Random -Maximum 10 }) } while ($b -match '^(\d)\1{8}$')
    $d1 = Digito $b @(10,9,8,7,6,5,4,3,2)
    $d2 = Digito "$b$d1" @(11,10,9,8,7,6,5,4,3,2)
    return "$b$d1$d2"
}
function Corpo($o) { $o | ConvertTo-Json -Compress }

$aTil = [string][char]0xE3
$marca = 'SMOKE' + (Get-Random -Maximum 1000000)
Write-Host "marca desta rodada: $marca"
$endereco = @{ logradouro = 'Rua das Flores'; cidade = "S$aTil" + 'o Paulo'; uf = 'sp'; cep = '01001-000' }

try {
    Write-Host '== saude'
    $r = Chamar GET '/actuator/health'
    Confere 'health UP' ($r.Status -eq 200 -and $r.Json.status -eq 'UP')

    Write-Host '== empresa e filial'
    $raiz = NovaRaiz
    $cnpjMatriz = NovoCnpj $raiz '0001'
    $r = Chamar POST '/api/empresas' (Corpo @{ cnpj = $cnpjMatriz; razaoSocial = "  $marca Ltda  " })
    Confere 'POST empresa 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $emp = $r.Json
    Confere 'razaoSocial aparada e Location da empresa' ($emp.razaoSocial -eq "$marca Ltda" -and $r.Location -like "*/api/empresas/$($emp.id)")
    $c = Chamar GET "/api/empresas/$($emp.id)"
    Confere 'criadoEm do GET igual ao do POST' ($c.Status -eq 200 -and $c.Json.criadoEm -eq $emp.criadoEm)
    $r = Chamar POST '/api/empresas' (Corpo @{ cnpj = $cnpjMatriz; razaoSocial = "$marca outra" })
    Confere 'CNPJ duplicado 409' ($r.Status -eq 409)
    $r = Chamar POST '/api/empresas' (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0002'); razaoSocial = "$marca filial" })
    Confere 'empresa que nao e matriz 422' ($r.Status -eq 422)
    $r = Chamar POST '/api/empresas' (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0001'); razaoSocial = ($marca + ($aTil * (200 - $marca.Length))) })
    Confere '200 caracteres acentuados cabem' ($r.Status -eq 201) "status=$($r.Status)"
    $r = Chamar POST "/api/empresas/$($emp.id)/filiais" (Corpo @{ cnpj = (NovoCnpj $raiz '0002'); nome = 'Filial Centro' })
    Confere 'POST filial 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $filialId = $r.Json.id
    $r = Chamar POST "/api/empresas/$($emp.id)/filiais" (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0002'); nome = 'Raiz diferente' })
    Confere 'filial com raiz diferente 422' ($r.Status -eq 422)

    Write-Host '== pessoa (o tipo e a separacao)'
    $pes = "/api/empresas/$($emp.id)/pessoas"
    $cpfCliente = NovoCpf
    $r = Chamar POST $pes (Corpo @{ tipo = 'cliente'; natureza = 'f'; documento = $cpfCliente; nome = 'Cliente Um' })
    Confere 'POST pessoa CLIENTE 201 (tipo e natureza normalizados)' ($r.Status -eq 201 -and $r.Json.tipo -eq 'CLIENTE' -and $r.Json.natureza -eq 'F') "status=$($r.Status) $($r.Texto)"
    $mascaraCpf = '***.' + $cpfCliente.Substring(3, 3) + '.' + $cpfCliente.Substring(6, 3) + '-**'
    Confere 'documento sai MASCARADO na resposta (o CPF inteiro nao aparece)' ($r.Json.documento -eq $mascaraCpf -and $r.Texto -notmatch $cpfCliente) "documento=$($r.Json.documento)"
    $idCliente = $r.Json.id
    $rv = Chamar GET "/api/pessoas/$idCliente/documento"
    Confere 'revelar devolve o documento inteiro (GET /api/pessoas/{id}/documento)' ($rv.Status -eq 200 -and $rv.Json.documento -eq $cpfCliente) "status=$($rv.Status)"
    $rv = Chamar PUT "/api/pessoas/$idCliente" (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = $mascaraCpf; nome = 'Cliente Um' })
    Confere 'atualizar devolvendo a mascara vale por "mesmo documento" (200)' ($rv.Status -eq 200) "status=$($rv.Status) $($rv.Texto)"
    $clienteId = $r.Json.id
    $r = Chamar POST $pes (Corpo @{ tipo = 'FORNECEDOR'; natureza = 'J'; documento = (NovoCnpj (NovaRaiz) '0001'); nome = 'Fornecedor Ltda' })
    Confere 'fornecedor pessoa juridica 201' ($r.Status -eq 201)
    $r = Chamar POST $pes (Corpo @{ tipo = 'COLABORADOR'; natureza = 'F'; documento = $cpfCliente; nome = 'Cliente Um'; cargo = 'Analista' })
    Confere 'mesmo CPF com outro tipo e outra linha (201)' ($r.Status -eq 201)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = $cpfCliente; nome = 'Repetido' })
    Confere 'mesmo CPF e mesmo tipo 409' ($r.Status -eq 409)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = (NovoCnpj (NovaRaiz) '0001'); nome = 'Doc errado' })
    Confere 'CNPJ com natureza F 400 no campo documento' ($r.Status -eq 400 -and $r.Json.erros[0].campo -eq 'documento')
    $r = Chamar POST $pes (Corpo @{ tipo = 'SOCIO'; natureza = 'F'; documento = (NovoCpf); nome = 'Socio A'; participacao = 60 })
    Confere 'socio 60% 201' ($r.Status -eq 201)
    $r = Chamar POST $pes (Corpo @{ tipo = 'SOCIO'; natureza = 'F'; documento = (NovoCpf); nome = 'Estoura'; participacao = 50 })
    Confere 'soma dos socios acima de 100% 422' ($r.Status -eq 422)
    $r = Chamar POST $pes (Corpo @{ tipo = 'SOCIO'; natureza = 'F'; documento = (NovoCpf); nome = 'Sem participacao' })
    Confere 'socio sem participacao 422' ($r.Status -eq 422)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = (NovoCpf); nome = 'Com cargo'; cargo = 'Chefe' })
    Confere 'cargo em quem nao e colaborador 422' ($r.Status -eq 422)
    $r = Chamar GET "$pes`?tipo=SOCIO"
    Confere 'filtro por tipo devolve so os socios' ($r.Status -eq 200 -and $r.Json.total -eq 1)

    Write-Host '== contato e endereco (de filial e de pessoa)'
    foreach ($dono in @("/api/filiais/$filialId", "/api/pessoas/$clienteId")) {
        $r = Chamar POST "$dono/contatos" (Corpo @{ tipo = 'EMAIL'; valor = 'FULANO@Exemplo.com.br' })
        Confere "$dono contato e-mail 201, mascarado e principal" ($r.Status -eq 201 -and $r.Json.valor -eq 'f***@exemplo.com.br' -and $r.Json.principal -eq $true) "status=$($r.Status) $($r.Texto)"
        $r = Chamar POST "$dono/contatos" (Corpo @{ tipo = 'TELEFONE'; valor = '(11) 98765-4321' })
        Confere "$dono contato telefone 201, mascarado" ($r.Status -eq 201 -and $r.Json.valor -eq '(11) *****-4321')
        $rv = Chamar GET "/api/contatos/$($r.Json.id)/valor"
        Confere "$dono revelar telefone devolve so digitos" ($rv.Status -eq 200 -and $rv.Json.valor -eq '11987654321') "status=$($rv.Status)"
        $r = Chamar POST "$dono/contatos" (Corpo @{ tipo = 'EMAIL'; valor = 'sem-arroba' })
        Confere "$dono e-mail invalido 400" ($r.Status -eq 400)
        $r = Chamar POST "$dono/enderecos" (Corpo $endereco)
        Confere "$dono endereco 201, UF maiuscula, CEP so digitos, principal" ($r.Status -eq 201 -and $r.Json.uf -eq 'SP' -and $r.Json.cep -eq '01001000' -and $r.Json.principal -eq $true) "status=$($r.Status) $($r.Texto)"
        $r = Chamar GET "$dono/contatos"
        Confere "$dono lista 2 contatos" ($r.Status -eq 200 -and $r.Json.total -eq 2)
    }

    Write-Host '== limites, conflitos e erros'
    $r = Chamar GET '/api/empresas?page=2147483647'
    Confere 'page gigante 400 (nao 500)' ($r.Status -eq 400)
    $r = Chamar GET '/api/empresas?sort=naoExiste,asc'
    Confere 'sort por campo inexistente 400' ($r.Status -eq 400)
    $r = Chamar GET "/api/empresas?razaoSocial=$([uri]::EscapeDataString('%'))&size=1"
    Confere 'filtro "%" nao vira curinga' ($r.Status -eq 200 -and $r.Json.total -eq 0)
    $r = Chamar POST '/api/empresas' -bytes (New-Object byte[] 70000)
    Confere 'corpo de 70000 bytes 413 problem+json' ($r.Status -eq 413 -and $r.Tipo -like 'application/problem+json*')
    $r = Chamar GET '/api/pessoas/999999999'
    Confere 'pessoa inexistente 404 problem+json' ($r.Status -eq 404 -and $r.Tipo -like 'application/problem+json*')
    $r = Chamar DELETE "/api/pessoas/$clienteId"
    Confere 'excluir pessoa com contato e endereco 409' ($r.Status -eq 409)
    $r = Chamar DELETE "/api/empresas/$($emp.id)"
    Confere 'excluir empresa com filial e pessoas 409' ($r.Status -eq 409)
    $r = Chamar POST "/api/empresas/$($emp.id)/inativar"
    Confere 'inativar empresa 200' ($r.Status -eq 200 -and $r.Json.ativa -eq $false)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = (NovoCpf); nome = 'Tarde demais' })
    Confere 'pessoa nova em empresa inativa 422' ($r.Status -eq 422)
}
finally {
    Write-Host '== limpeza (tudo que tem a marca da rodada)'
    $apagadas = 0
    $lista = Chamar GET "/api/empresas?razaoSocial=$marca&size=100"
    foreach ($e in @($lista.Json.itens)) {
        $donos = @()
        foreach ($f in @((Chamar GET "/api/empresas/$($e.id)/filiais?size=100").Json.itens)) { $donos += [pscustomobject]@{ base = "/api/filiais/$($f.id)" } }
        foreach ($p in @((Chamar GET "/api/empresas/$($e.id)/pessoas?size=100").Json.itens)) { $donos += [pscustomobject]@{ base = "/api/pessoas/$($p.id)" } }
        foreach ($d in $donos) {
            foreach ($tipo in @('contatos', 'enderecos')) {
                $itens = @((Chamar GET "$($d.base)/$tipo`?size=100").Json.itens)
                # o principal so sai por ultimo: primeiro os demais
                foreach ($i in ($itens | Where-Object { -not $_.principal })) { [void](Chamar DELETE "/api/$tipo/$($i.id)") }
                foreach ($i in ($itens | Where-Object { $_.principal })) { [void](Chamar DELETE "/api/$tipo/$($i.id)") }
            }
            [void](Chamar DELETE $d.base)
        }
        $x = Chamar DELETE "/api/empresas/$($e.id)"
        if ($x.Status -eq 204) { $apagadas++ }
    }
    $resto = Chamar GET "/api/empresas?razaoSocial=$marca&size=100"
    Confere "limpeza: $apagadas empresa(s) apagada(s), nada sobrou com a marca" ($resto.Json.total -eq 0) "sobraram=$($resto.Json.total)"
}

Write-Host ''
Write-Host "RESULTADO: $($script:ok) ok, $($script:falhas.Count) falha(s)"
Write-Host '(as linhas de LOG desta rodada ficam no banco: a API nao tem como apaga-las, e esse e o proposito)'
if ($script:falhas.Count -gt 0) { $script:falhas | ForEach-Object { Write-Host "  - $_" }; exit 1 }
