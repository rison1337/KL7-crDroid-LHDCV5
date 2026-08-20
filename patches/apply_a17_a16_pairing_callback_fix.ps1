param(
    [Parameter(Mandatory = $true)] [string] $InputFile,
    [Parameter(Mandatory = $true)] [string] $OutputFile
)

# Android 17 native callbacks contain arguments that are absent from the
# Android 16 Java JNI contract used by this ROM:
#
# SSP A17:  address, transport, pairing_variant, passkey, algorithm
# SSP A16:  address, pairing_variant, passkey
# Bond A17: status, address, transport, state, algorithm, variant, initiator, reason
# Bond A16: status, address, state, reason
#
# The register choices below are specific to the verified KL7 build binary.
$patches = @(
    @{ Offset = 0x3e8330; Old = 'c51e0012'; New = 'e503142a' }, # w5 = passkey (w20)
    @{ Offset = 0x3e8334; Old = 'e403152a'; New = 'e403162a' }, # w4 = variant (w22)
    @{ Offset = 0x3e8550; Old = 'c51e0012'; New = 'e503132a' }, # w5 = state (w19)
    @{ Offset = 0x3e855c; Old = 'e603132a'; New = 'e603172a' }  # w6 = reason (w23)
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
Write-Output ('Patched {0} Android 17/16 pairing callback instructions -> {1}' -f $patches.Count, $outputPath)
