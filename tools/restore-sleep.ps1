<#
.SYNOPSIS
    Вернуть настройки сна такими, какими они были до прогона.

.DESCRIPTION
    Парный к no-sleep.ps1. Читает файл с прежним состоянием и вписывает обратно три
    настройки активной схемы: сон по бездействию, гибернацию по бездействию и действие при
    закрытии крышки.

    ВОЗВРАТ ИДЁТ ЗАПИСЬЮ, А НЕ СТИРАНИЕМ, и это не лень. Ключи схемы питания защищены:
    `Remove-ItemProperty` отвечает «Requested registry access is not allowed» даже
    администратору. Поэтому первый скрипт сохраняет ДЕЙСТВУЮЩЕЕ значение (своё у схемы, а
    если своего не было — её умолчание), а этот вписывает его назад. Машина ведёт себя
    ровно как прежде; разница лишь в том, что значение стало явным вместо унаследованного.

    Файл состояния после успешного возврата удаляется: он же служит первому скрипту
    признаком «бодрствование уже включено».

    ВНИМАНИЕ: файл должен лежать в UTF-8 С BOM — см. no-sleep.ps1.
#>
[CmdletBinding()]
param(
    [string] $StateFile = (Join-Path $PSScriptRoot 'no-sleep-state.json')
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Нужны права администратора: настройки схемы питания лежат в HKLM.'
}

if (-not (Test-Path $StateFile)) {
    Write-Host 'Возвращать нечего: файла прежнего состояния нет — значит, его и не меняли.'
    exit 0
}

$state = Get-Content -Path $StateFile -Raw -Encoding utf8 | ConvertFrom-Json
$scheme = $state.scheme

foreach ($value in $state.values) {
    if ($null -ne $value.ac) {
        powercfg /setacvalueindex $scheme $value.sub $value.guid ([int] $value.ac) | Out-Null
    }
    if ($null -ne $value.dc) {
        powercfg /setdcvalueindex $scheme $value.sub $value.guid ([int] $value.dc) | Out-Null
    }
    Write-Host ("вернули — {0}: от сети {1}, от батареи {2}" -f $value.name, $value.ac, $value.dc)
}

powercfg /setactive $scheme | Out-Null
Remove-Item -Path $StateFile -Force

Write-Host ''
Write-Host 'Настройки сна вернулись к тому, что было до прогона.'
