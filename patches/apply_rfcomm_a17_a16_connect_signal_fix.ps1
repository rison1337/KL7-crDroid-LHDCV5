param(
    [Parameter(Mandatory = $true)] [string] $InputFile,
    [Parameter(Mandatory = $true)] [string] $OutputFile
)

$patches = @(
    # Inlined client send_app_connect_signal path.
    @{ Offset = 0x707e30; Old = '8a038052'; New = '8a058052' },
    @{ Offset = 0x707ec0; Old = 'e80240b9'; New = 'e80240f9' },
    @{ Offset = 0x707ec4; Old = 'e90a4079'; New = 'aa9300d1' },
    @{ Offset = 0x707ed0; Old = '82038052'; New = '82058052' },
    @{ Offset = 0x707ed4; Old = 'b8831db8'; New = '280300f9' },
    @{ Offset = 0x707ed8; Old = '280300b9'; New = 'b8831db8' },
    @{ Offset = 0x707edc; Old = '290b0079'; New = '5f7d00a9' },
    @{ Offset = 0x707ee0; Old = 'bfc31df8'; New = '5f5101a9' },
    # Rewrite the size after the address lookup/logging path; that path uses
    # stack scratch space and the Java side must see 44 in the first field.
    @{ Offset = 0x707ee4; Old = 'b4431ef8'; New = '22000079' },
    @{ Offset = 0x707ef0; Old = '1f700071'; New = '1fb00071' },

    # Shared server/FD-transfer send_app_connect_signal path. The 44-byte
    # structure is moved to sp+4 so it cannot overlap the saved frame record.
    @{ Offset = 0x707ff4; Old = 'aa9300d1'; New = 'ea130091' },
    @{ Offset = 0x707ffc; Old = 'f403032a'; New = '74040011' },
    @{ Offset = 0x708020; Old = '88038052'; New = '88058052' },
    @{ Offset = 0x708024; Old = 'a8c31d78'; New = 'e80b0079' },
    @{ Offset = 0x7080a4; Old = 'e80240b9'; New = 'e80240f9' },
    @{ Offset = 0x7080a8; Old = 'e90a4079'; New = 'e9430091' },
    @{ Offset = 0x7080ac; Old = '9f060031'; New = 'e1130091' },
    @{ Offset = 0x7080b0; Old = 'a19300d1'; New = 'e003132a' },
    @{ Offset = 0x7080b4; Old = 'e003132a'; New = '82058052' },
    @{ Offset = 0x7080b8; Old = '82038052'; New = '22000079' },
    @{ Offset = 0x7080bc; Old = 'b6431eb8'; New = '280300f9' },
    @{ Offset = 0x7080c0; Old = '280300b9'; New = 'f60f00b9' },
    @{ Offset = 0x7080c4; Old = '290b0079'; New = '3f7d00a9' },
    @{ Offset = 0x7080c8; Old = 'bfd73ea9'; New = '3f5501a9' },
    @{ Offset = 0x7080cc; Old = '80000054'; New = '94000034' },
    @{ Offset = 0x7080d0; Old = 'e303142a'; New = '83060051' },
    @{ Offset = 0x7080e8; Old = '1f700071'; New = '1fb00071' }
)

function Convert-HexToBytes([string] $Hex) {
    $result = [byte[]]::new($Hex.Length / 2)
    for ($i = 0; $i -lt $result.Length; $i++) {
        $result[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16)
    }
    return $result
}

$bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InputFile))

foreach ($entry in $patches) {
    $old = Convert-HexToBytes $entry.Old
    $new = Convert-HexToBytes $entry.New
    $actual = [byte[]]::new($old.Length)
    [Array]::Copy($bytes, $entry.Offset, $actual, 0, $old.Length)
    $actualHex = ([BitConverter]::ToString($actual) -replace '-', '').ToLowerInvariant()
    if ($actualHex -ne $entry.Old) {
        throw ('Unexpected bytes at 0x{0:x}: expected {1}, got {2}' -f $entry.Offset, $entry.Old, $actualHex)
    }
    [Array]::Copy($new, 0, $bytes, $entry.Offset, $new.Length)
}

$outputPath = [IO.Path]::GetFullPath($OutputFile)
[IO.File]::WriteAllBytes($outputPath, $bytes)
Write-Output ('Patched {0} instructions -> {1}' -f $patches.Count, $outputPath)
