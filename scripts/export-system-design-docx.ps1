param(
    [string]$MarkdownFile = "docs/SYSTEM_DESIGN.md",
    [string]$OutputFile = "docs/活动管家系统设计说明书.docx"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$markdown = Join-Path $root $MarkdownFile
$output = Join-Path $root $OutputFile
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$doc = $word.Documents.Add()
$selection = $word.Selection

function Add-Text([string]$text, [int]$style = -1) {
    $selection.Style = $doc.Styles.Item($style)
    $selection.TypeText($text)
    $selection.TypeParagraph()
}

try {
    foreach($line in Get-Content -LiteralPath $markdown -Encoding UTF8) {
        if ($line -match '^!\[[^]]*\]\(([^)]+)\)$') {
            $imagePath = Join-Path (Split-Path $markdown) $matches[1]
            if (Test-Path $imagePath) {
                $shape = $selection.InlineShapes.AddPicture($imagePath)
                if ($shape.Width -gt 470) { $shape.LockAspectRatio = -1; $shape.Width = 470 }
                $selection.TypeParagraph()
            }
        } elseif ($line -match '^# (.+)$') { Add-Text $matches[1] -63
        } elseif ($line -match '^## (.+)$') { Add-Text $matches[1] -2
        } elseif ($line -match '^### (.+)$') { Add-Text $matches[1] -3
        } elseif ($line -match '^#### (.+)$') { Add-Text $matches[1] -4
        } elseif ($line -match '^[-*] (.+)$') { Add-Text ("• " + $matches[1]) -1
        } elseif ($line -match '^\d+\. (.+)$') { Add-Text $line -1
        } elseif ($line -match '^> (.+)$') { Add-Text $matches[1] -1
        } elseif ($line -match '^```') { continue
        } elseif ($line -match '^\|') { Add-Text ($line.Trim('|').Replace('|','    ')) -1
        } elseif ([string]::IsNullOrWhiteSpace($line)) { $selection.TypeParagraph()
        } else {
            $plain = $line.Replace('`','').Replace('**','')
            Add-Text $plain -1
        }
    }
    $doc.SaveAs2($output, 16)
}
finally {
    try { $doc.Close() } catch { }
    try { $word.Quit() } catch { }
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($doc) | Out-Null
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
    [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}

Write-Host "Word document generated: $output"
