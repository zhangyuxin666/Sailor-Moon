param(
    [string]$Vsdx = "docs/diagrams/ActivityAssistant-SystemDesign.vsdx",
    [string]$OutDir = "docs/diagrams/system-design"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$vsdxPath = Join-Path $root $Vsdx
$outPath = Join-Path $root $OutDir

if (-not (Test-Path $vsdxPath)) { throw "找不到 Visio 源文件: $vsdxPath" }
if (-not (Test-Path $outPath)) { New-Item -ItemType Directory -Path $outPath | Out-Null }

# Visio 页面名 -> 输出文件名（与 SYSTEM_DESIGN.md 中的引用保持一致）
$map = [ordered]@{
    "总体架构"     = "01-overall-architecture.png"
    "活动全流程"   = "02-activity-workflow.png"
    "RAG时序"      = "03-rag-sequence.png"
    "实时大屏"     = "04-realtime-analytics.png"
    "部署拓扑"     = "05-deployment-topology.png"
    "核心数据模型" = "06-core-data-model.png"
}

$visio = $null
$doc = $null
try {
    try {
        $visio = [Runtime.InteropServices.Marshal]::GetActiveObject("Visio.Application")
        Write-Host "复用已打开的 Visio 实例"
    } catch {
        $visio = New-Object -ComObject Visio.Application
        $visio.Visible = $false
        Write-Host "新建 Visio 实例"
    }

    $doc = $visio.Documents.OpenEx($vsdxPath, 2)   # 2 = visOpenRO
    Write-Host ("打开: {0}，共 {1} 页" -f $doc.Name, $doc.Pages.Count)

    foreach ($page in $doc.Pages) {
        $name = $page.Name
        if (-not $map.Contains($name)) {
            Write-Host ("  跳过未登记页面: {0}" -f $name)
            continue
        }
        $target = Join-Path $outPath $map[$name]
        if (Test-Path $target) { Remove-Item $target -Force }
        $page.Export($target)
        Write-Host ("  {0} -> {1} ({2:N0} bytes)" -f $name, $map[$name], (Get-Item $target).Length)
    }
}
finally {
    if ($doc) { try { $doc.Close() } catch { } }
    if ($visio -and $visio.Visible -eq $false) { try { $visio.Quit() } catch { } }
}

# 去掉四周白边，让嵌入 Word 时图形更大、字更清楚
$python = "C:\Users\Masijia\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if (Test-Path $python) {
    $trim = @'
import sys
from pathlib import Path
from PIL import Image, ImageChops

for p in sorted(Path(sys.argv[1]).glob("*.png")):
    im = Image.open(p).convert("RGB")
    bg = Image.new("RGB", im.size, (255, 255, 255))
    diff = ImageChops.difference(im, bg)
    bbox = diff.getbbox()
    if not bbox:
        continue
    pad = 24
    left = max(0, bbox[0] - pad)
    top = max(0, bbox[1] - pad)
    right = min(im.width, bbox[2] + pad)
    bottom = min(im.height, bbox[3] + pad)
    if (right - left, bottom - top) == im.size:
        continue
    im.crop((left, top, right, bottom)).save(p)
    print(f"  trimmed {p.name}: {im.width}x{im.height} -> {right-left}x{bottom-top}")
'@
    $trimFile = Join-Path $env:TEMP "trim_diagrams.py"
    [System.IO.File]::WriteAllText($trimFile, $trim, (New-Object System.Text.UTF8Encoding($false)))
    & $python $trimFile $outPath
}

Write-Host "完成：$outPath"
