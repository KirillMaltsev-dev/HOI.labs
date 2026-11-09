param(
    [switch]$Test,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ProgramArgs
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $OutputEncoding
[Console]::OutputEncoding = $OutputEncoding
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot/src" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($Test) {
    $sources += @(Get-ChildItem -LiteralPath "$PSScriptRoot/test" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
}
$outputDirectory = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
& javac -encoding UTF-8 -Xlint:all -Werror -d $outputDirectory @sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
if ($Test) {
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $outputDirectory lab2.ListChecks
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $outputDirectory lab2.AppChecks
} else {
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $outputDirectory lab2.Main @ProgramArgs
}
exit $LASTEXITCODE
