<#
.SYNOPSIS
  Roteiro de fumaca por HTTP real contra a API em execucao.

.DESCRIPTION
  Os testes de integracao rodam dentro de uma transacao que e desfeita, e isso esconde erros que so
  aparecem em producao (como LazyInitializationException fora da transacao). Este roteiro chama a
  API de verdade: cria cliente, endereco, contato, empresa, filial e socios, viola as regras de
  proposito e confere o status de cada resposta. No fim apaga TUDO que criou (todo cliente criado
  tem a marca da rodada na razao social) e confere que nada sobrou.

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
$saoPaulo = "S$aTil" + 'o Paulo'
$marca = 'SMOKE' + (Get-Random -Maximum 1000000)
Write-Host "marca desta rodada: $marca"

try {
    Write-Host '== saude'
    $r = Chamar GET '/actuator/health'
    Confere 'health UP' ($r.Status -eq 200 -and $r.Json.status -eq 'UP')

    Write-Host '== cliente'
    $raiz = NovaRaiz
    $cnpjMatriz = NovoCnpj $raiz '0001'
    $formatado = '{0}.{1}.{2}/{3}-{4}' -f $cnpjMatriz.Substring(0,2), $cnpjMatriz.Substring(2,3), $cnpjMatriz.Substring(5,3), $cnpjMatriz.Substring(8,4), $cnpjMatriz.Substring(12,2)
    $r = Chamar POST '/api/clientes' (Corpo @{ cnpj = $formatado; razaoSocial = "  $marca Ltda  " })
    Confere 'POST cliente 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $cli = $r.Json
    Confere 'CNPJ gravado so com digitos' ($cli.cnpj -eq $cnpjMatriz)
    Confere 'razaoSocial aparada' ($cli.razaoSocial -eq "$marca Ltda")
    Confere 'Location aponta para o cliente' ($r.Location -like "*/api/clientes/$($cli.id)")
    $c = Chamar GET "/api/clientes/$($cli.id)"
    Confere 'GET cliente 200' ($c.Status -eq 200)
    Confere 'criadoEm do GET igual ao do POST' ($c.Json.criadoEm -eq $cli.criadoEm) "post=$($cli.criadoEm) get=$($c.Json.criadoEm)"
    Confere 'atualizadoEm do GET igual ao do POST' ($c.Json.atualizadoEm -eq $cli.atualizadoEm)

    $r = Chamar POST '/api/clientes' (Corpo @{ cnpj = $cnpjMatriz; razaoSocial = "$marca outro" })
    Confere 'CNPJ duplicado 409' ($r.Status -eq 409)
    $r = Chamar POST '/api/clientes' (Corpo @{ cnpj = ('abc' + (NovoCnpj (NovaRaiz) '0001') + 'xyz'); razaoSocial = "$marca lixo" })
    Confere 'CNPJ com letras 400' ($r.Status -eq 400 -and $r.Tipo -like 'application/problem+json*')
    $r = Chamar POST '/api/clientes' (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0001'); razaoSocial = ($marca + ($aTil * (200 - $marca.Length))) })
    Confere '200 caracteres acentuados cabem (V4)' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $r = Chamar POST '/api/clientes' (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0001'); razaoSocial = ($marca + ($aTil * (201 - $marca.Length))) })
    Confere '201 caracteres 400' ($r.Status -eq 400)

    Write-Host '== endereco'
    $end = "/api/clientes/$($cli.id)/enderecos"
    $r = Chamar POST $end (Corpo @{ logradouro = 'Rua das Flores'; cidade = $saoPaulo; uf = 'sp'; cep = '01001-000'; principal = $false })
    Confere 'POST endereco 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    Confere 'primeiro endereco vira principal' ($r.Json.principal -eq $true)
    Confere 'UF em maiuscula e CEP so digitos' ($r.Json.uf -eq 'SP' -and $r.Json.cep -eq '01001000')
    $e1 = $r.Json.id
    $r = Chamar POST $end (Corpo @{ logradouro = 'Av Paulista'; cidade = $saoPaulo; uf = 'SP'; cep = '01310100'; principal = $true })
    Confere 'segundo endereco principal 201' ($r.Status -eq 201)
    $r = Chamar GET $end
    $principais = @($r.Json.itens | Where-Object { $_.principal }).Count
    Confere 'dois enderecos, um so principal' ($r.Json.total -eq 2 -and $principais -eq 1)
    $r = Chamar POST $end (Corpo @{ logradouro = 'X'; cidade = 'Y'; uf = 'XX'; cep = '01001000' })
    Confere 'UF inexistente 400' ($r.Status -eq 400)

    Write-Host '== contato'
    $cont = "/api/clientes/$($cli.id)/contatos"
    $r = Chamar POST $cont (Corpo @{ nome = 'Fulano'; email = 'FULANO@Exemplo.com.br' })
    Confere 'POST contato 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    Confere 'e-mail em minuscula' ($r.Json.email -eq 'fulano@exemplo.com.br')
    $r = Chamar POST $cont (Corpo @{ nome = 'Sem meio' })
    Confere 'contato sem e-mail nem telefone 422' ($r.Status -eq 422)

    Write-Host '== empresa e filial'
    $emp = "/api/clientes/$($cli.id)/empresas"
    $r = Chamar POST $emp (Corpo @{ cnpj = $cnpjMatriz; razaoSocial = "$marca Matriz" })
    Confere 'POST empresa 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $empId = $r.Json.id
    $r = Chamar POST $emp (Corpo @{ cnpj = (NovoCnpj $raiz '0002'); razaoSocial = "$marca Filial como empresa" })
    Confere 'empresa que nao e matriz 422' ($r.Status -eq 422)
    $r = Chamar POST $emp (Corpo @{ cnpj = $cnpjMatriz; razaoSocial = "$marca Dup" })
    Confere 'empresa CNPJ duplicado 409' ($r.Status -eq 409)
    $fil = "/api/empresas/$empId/filiais"
    $r = Chamar POST $fil (Corpo @{ cnpj = (NovoCnpj $raiz '0002'); nome = 'Filial Centro' })
    Confere 'POST filial 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $r = Chamar POST $fil (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0002'); nome = 'Raiz diferente' })
    Confere 'filial com raiz diferente 422' ($r.Status -eq 422)
    $r = Chamar POST $fil (Corpo @{ cnpj = $cnpjMatriz; nome = 'Ordem da matriz' })
    Confere 'filial com ordem 0001 422' ($r.Status -eq 422)

    Write-Host '== socios'
    $soc = "/api/empresas/$empId/socios"
    $cpfA = NovoCpf
    $r = Chamar POST $soc (Corpo @{ nome = 'Primeiro'; cpf = $cpfA; participacao = 60 })
    Confere 'POST socio 60% 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $r = Chamar POST $soc (Corpo @{ nome = 'Estoura'; cpf = (NovoCpf); participacao = 50 })
    Confere 'soma acima de 100% 422' ($r.Status -eq 422)
    $r = Chamar POST $soc (Corpo @{ nome = 'Repetido'; cpf = $cpfA; participacao = 50 })
    Confere 'CPF repetido (e soma estourada) responde 409' ($r.Status -eq 409)
    $r = Chamar POST $soc (Corpo @{ nome = 'Segundo'; cpf = (NovoCpf); participacao = 40 })
    Confere 'socio 40% completa 100% e passa' ($r.Status -eq 201)
    $r = Chamar GET $soc
    Confere 'dois socios listados' ($r.Json.total -eq 2)

    Write-Host '== listagens'
    $r = Chamar GET "/api/clientes?razaoSocial=$marca&size=100"
    Confere 'filtro por razao social acha os 2 clientes da rodada' ($r.Status -eq 200 -and $r.Json.total -eq 2) "total=$($r.Json.total)"
    $r = Chamar GET '/api/clientes?page=2147483647'
    Confere 'page gigante 400 (nao 500)' ($r.Status -eq 400) "status=$($r.Status)"
    $r = Chamar GET '/api/clientes?sort=naoExiste,asc'
    Confere 'sort por campo inexistente 400' ($r.Status -eq 400) "status=$($r.Status)"
    $r = Chamar GET '/api/clientes?size=1000'
    Confere 'size acima do maximo e limitado a 100' ($r.Status -eq 200 -and $r.Json.tamanho -eq 100)
    $r = Chamar GET "/api/clientes?razaoSocial=$([uri]::EscapeDataString('%'))&size=1"
    Confere 'filtro "%" nao vira curinga (so acha razao social com % literal)' ($r.Status -eq 200 -and $r.Json.total -eq 0) "total=$($r.Json.total)"
    $r = Chamar GET '/api/clientes/999999999'
    Confere 'cliente inexistente 404' ($r.Status -eq 404 -and $r.Tipo -like 'application/problem+json*')
    foreach ($caminho in @("/api/empresas/$empId", "/api/empresas/$empId/filiais", "/api/clientes/$($cli.id)/empresas", "/api/clientes/$($cli.id)/contatos")) {
        $r = Chamar GET $caminho
        Confere "GET $caminho 200 (sem LazyInitialization)" ($r.Status -eq 200) "status=$($r.Status)"
    }

    Write-Host '== limites e conflitos'
    $r = Chamar POST '/api/clientes' -bytes (New-Object byte[] 70000)
    Confere 'corpo de 70000 bytes 413 problem+json' ($r.Status -eq 413 -and $r.Tipo -like 'application/problem+json*') "status=$($r.Status)"
    $r = Chamar DELETE "/api/empresas/$empId"
    Confere 'excluir empresa com filiais e socios 409' ($r.Status -eq 409)
    $r = Chamar DELETE "/api/clientes/$($cli.id)"
    Confere 'excluir cliente com dependentes 409' ($r.Status -eq 409)
    $r = Chamar POST "/api/clientes/$($cli.id)/inativar"
    Confere 'inativar cliente 200' ($r.Status -eq 200 -and $r.Json.ativo -eq $false)
    $r = Chamar POST $end (Corpo @{ logradouro = 'Nova'; cidade = 'SP'; uf = 'SP'; cep = '01001000' })
    Confere 'endereco novo em cliente inativo 422' ($r.Status -eq 422)
    $r = Chamar PUT "/api/clientes/$($cli.id)" (Corpo @{ cnpj = $cnpjMatriz; razaoSocial = "$marca Renomeado" })
    Confere 'PUT em cliente inativo e permitido e nao reativa' ($r.Status -eq 200 -and $r.Json.ativo -eq $false)
    $r = Chamar POST "/api/clientes/$($cli.id)/reativar"
    Confere 'reativar cliente 200' ($r.Status -eq 200 -and $r.Json.ativo -eq $true)
}
finally {
    Write-Host '== limpeza (tudo que tem a marca da rodada)'
    $apagados = 0
    $lista = Chamar GET "/api/clientes?razaoSocial=$marca&size=100"
    foreach ($cl in @($lista.Json.itens)) {
        $empresas = Chamar GET "/api/clientes/$($cl.id)/empresas?size=100"
        foreach ($em in @($empresas.Json.itens)) {
            foreach ($s in @((Chamar GET "/api/empresas/$($em.id)/socios?size=100").Json.itens)) { [void](Chamar DELETE "/api/socios/$($s.id)") }
            foreach ($f in @((Chamar GET "/api/empresas/$($em.id)/filiais?size=100").Json.itens)) { [void](Chamar DELETE "/api/filiais/$($f.id)") }
            [void](Chamar DELETE "/api/empresas/$($em.id)")
        }
        foreach ($tipo in @('enderecos', 'contatos')) {
            $itens = @((Chamar GET "/api/clientes/$($cl.id)/$tipo`?size=100").Json.itens)
            foreach ($i in ($itens | Where-Object { -not $_.principal })) { [void](Chamar DELETE "/api/clientes/$($cl.id)/$tipo/$($i.id)") }
            foreach ($i in ($itens | Where-Object { $_.principal })) { [void](Chamar DELETE "/api/clientes/$($cl.id)/$tipo/$($i.id)") }
        }
        $d = Chamar DELETE "/api/clientes/$($cl.id)"
        if ($d.Status -eq 204) { $apagados++ }
    }
    $resto = Chamar GET "/api/clientes?razaoSocial=$marca&size=100"
    Confere "limpeza: $apagados cliente(s) apagado(s), nada sobrou com a marca" ($resto.Json.total -eq 0) "sobraram=$($resto.Json.total)"
}

Write-Host ''
Write-Host "RESULTADO: $($script:ok) ok, $($script:falhas.Count) falha(s)"
if ($script:falhas.Count -gt 0) { $script:falhas | ForEach-Object { Write-Host "  - $_" }; exit 1 }
