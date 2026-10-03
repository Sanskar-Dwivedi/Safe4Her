# Build-size conversion of the existing branded icon. No artwork redesign.
# Run on Windows; the generated PNG assets are committed for Xcode/macOS builds.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$iconDirectory = Join-Path $PSScriptRoot 'Security4Her/Assets.xcassets/AppIcon.appiconset'
$sourcePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'web-install/apple-touch-icon.png'
$source = [System.Drawing.Bitmap]::new($sourcePath)
try {
    $images = @()
    foreach ($slot in @(@(20, 2), @(20, 3), @(29, 2), @(29, 3), @(40, 2), @(40, 3), @(60, 2), @(60, 3), @(1024, 1))) {
        $pixels = $slot[0] * $slot[1]
        $filename = "AppIcon-$pixels.png"
        $bitmap = [System.Drawing.Bitmap]::new($pixels, $pixels, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear($source.GetPixel(0, 0))
            $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.DrawImage($source, 0, 0, $pixels, $pixels)
            $bitmap.Save((Join-Path $iconDirectory $filename), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
        $images += @{ filename = $filename; idiom = $(if ($pixels -eq 1024) { 'ios-marketing' } else { 'iphone' }); size = "$($slot[0])x$($slot[0])"; scale = "$($slot[1])x" }
    }
    @{ images = $images; info = @{ author = 'xcode'; version = 1 } } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $iconDirectory 'Contents.json') -Encoding utf8
} finally { $source.Dispose() }
