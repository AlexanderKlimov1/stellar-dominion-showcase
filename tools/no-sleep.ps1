<#
.SYNOPSIS
    Не давать машине уснуть, пока идёт балансовый прогон, — даже с закрытой крышкой.

.DESCRIPTION
    Заведено после круга 7: замер шёл девять часов вместо пяти, и виноват был не код.
    Hikari написал в журнал сервера «Thread starvation or clock leap detected
    (housekeeper delta=4h19m38s)» — его сторож, который ходит раз в полминуты, не
    просыпался четыре часа двадцать минут. Это не нагрузка, это сон компьютера: прогон
    всё это время стоял. Сон по бездействию от сети был уже отключён, так что усыпила
    машину крышка: у действия крышки своего значения в схеме не было, а умолчание схемы —
    «спать» (проверено, AC=1, DC=1).

    Скрипт трогает ровно три настройки активной схемы, и обе их половины (от сети и от
    батареи): сон по бездействию, гибернацию по бездействию и действие при закрытии
    крышки. Экран не трогается вовсе — гаснуть он может, прогону это не мешает.

    ПРЕЖНЕЕ СОСТОЯНИЕ СОХРАНЯЕТСЯ В ФАЙЛ, и сохраняется оно ДЕЙСТВУЮЩИМ ЧИСЛОМ, а не
    признаком «значения не было». Причина простая и выяснена на своей шкуре: у части
    настроек своего значения в схеме нет, действует умолчание, — и вернуть такое состояние
    стиранием НЕЛЬЗЯ. Ключи схемы питания защищены, и `Remove-ItemProperty` отвечает
    «Requested registry access is not allowed» даже администратору. Поэтому здесь
    вычисляется действующее значение (своё, а если его нет — умолчание схемы), и возврат
    просто вписывает его обратно. Поведение машины от этого то же самое; разница лишь в
    том, что значение станет явным вместо унаследованного.

    Читается всё из реестра, а не из вывода powercfg: вывод переведён на язык системы, и
    разбирать его значило бы привязать скрипт к локали.

    Парный скрипт — restore-sleep.ps1. Его надо запускать после прогона, иначе машина
    останется бодрствующей навсегда.

    ВНИМАНИЕ: файл должен лежать в UTF-8 С BOM. Windows PowerShell 5.1 без BOM читает
    .ps1 в кодировке системы, и кириллица в комментариях рассыпается прямо на разборе.
#>
[CmdletBinding()]
param(
    # Куда положить прежнее состояние. Рядом со скриптом: пара скриптов должна находить
    # файл сама, без аргументов.
    [string] $StateFile = (Join-Path $PSScriptRoot 'no-sleep-state.json')
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Нужны права администратора: настройки схемы питания лежат в HKLM.'
}

# Подгруппы и настройки схемы питания Windows. Идентификаторы постоянные, от языка
# системы не зависят.
$SUB_SLEEP   = '238c9fa8-0aad-41ed-83f4-97be242c8f20'
$SUB_BUTTONS = '4f971e89-eebd-4455-a8de-9e59040e7347'
$SETTINGS = @(
    @{ Name = 'сон по бездействию';           Sub = $SUB_SLEEP;   Guid = '29f6c1db-86da-48c5-9fdb-f2b67b1f44da' },
    @{ Name = 'гибернация по бездействию';    Sub = $SUB_SLEEP;   Guid = '9d7815a6-7ee4-497e-8888-515a05f02364' },
    @{ Name = 'действие при закрытии крышки'; Sub = $SUB_BUTTONS; Guid = '5ca83367-6e45-459f-a27b-476b1d01c936' }
)

if (Test-Path $StateFile) {
    Write-Host 'Бодрствование уже включено: файл прежнего состояния на месте.'
    Write-Host 'Если это ошибка, сперва верните настройки: restore-sleep.ps1'
    exit 0
}

$scheme = (powercfg /getactivescheme) -replace '.*([0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}).*', '$1'
if (-not $scheme) {
    throw 'Не удалось определить активную схему питания.'
}

function Get-EffectiveIndex {
    <#
        Действующее значение настройки: своё у схемы, а если своего нет — умолчание схемы.
        Именно его и надо будет вернуть: стереть своё значение нельзя, ключи защищены.
    #>
    param([string] $Scheme, [string] $Sub, [string] $Guid, [string] $Which)

    $own = "HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes\$Scheme\$Sub\$Guid"
    if (Test-Path $own) {
        $item = Get-ItemProperty -Path $own
        if ($item.PSObject.Properties.Name -contains "${Which}SettingIndex") {
            return [int] $item."${Which}SettingIndex"
        }
    }
    $fallback = "HKLM:\SYSTEM\CurrentControlSet\Control\Power\PowerSettings\$Sub\$Guid\DefaultPowerSchemeValues\$Scheme"
    if (Test-Path $fallback) {
        $item = Get-ItemProperty -Path $fallback
        if ($item.PSObject.Properties.Name -contains "${Which}SettingIndex") {
            return [int] $item."${Which}SettingIndex"
        }
    }
    return $null
}

$state = [ordered]@{
    scheme  = $scheme
    savedAt = (Get-Date).ToString('s')
    values  = @()
}

foreach ($setting in $SETTINGS) {
    $ac = Get-EffectiveIndex -Scheme $scheme -Sub $setting.Sub -Guid $setting.Guid -Which 'AC'
    $dc = Get-EffectiveIndex -Scheme $scheme -Sub $setting.Sub -Guid $setting.Guid -Which 'DC'
    $state.values += [ordered]@{
        name = $setting.Name
        sub  = $setting.Sub
        guid = $setting.Guid
        ac   = $ac
        dc   = $dc
    }
    Write-Host ("было — {0}: от сети {1}, от батареи {2}" -f $setting.Name, $ac, $dc)
}

$state | ConvertTo-Json -Depth 4 | Out-File -FilePath $StateFile -Encoding utf8

foreach ($setting in $SETTINGS) {
    # Ноль значит и «никогда не засыпать», и «при закрытии крышки не делать ничего»:
    # у обеих настроек ноль — это бездействие.
    powercfg /setacvalueindex $scheme $setting.Sub $setting.Guid 0 | Out-Null
    powercfg /setdcvalueindex $scheme $setting.Sub $setting.Guid 0 | Out-Null
}
powercfg /setactive $scheme | Out-Null

Write-Host ''
Write-Host 'Машина не уснёт: сон и гибернация по бездействию выключены, крышка ничего не делает.'
Write-Host "Прежнее состояние записано: $StateFile"
Write-Host 'После прогона верните как было: restore-sleep.ps1'
