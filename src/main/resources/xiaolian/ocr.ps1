# QQ三国助手 —— 内置 OCR 小工具（Windows.Media.Ocr）
#
# 由 Java 侧（OcrLite.java）在运行时从 jar 释放到临时目录后调用：
#   powershell -NoProfile -ExecutionPolicy Bypass -File ocr.ps1 -Dir <图片目录> -Out <结果文件> [-Lang zh-Hans-CN]
#
# 用系统自带的 OCR 引擎（Win10/11 中文系统自带 zh-Hans-CN），不依赖任何第三方库。
# 输出格式（每行一条，`|` 分隔，正文里不会出现 `|`）：
#   T_ENGINE_MS=<引擎创建耗时>
#   FILE=<文件名>|W=<宽>|H=<高>|MS=<识别耗时>
#   LINE=<x0>,<y0>,<x1>,<y1>|<识别出的文字>
#   T_TOTAL_MS=<总耗时>
#   FATAL=<错误>

param(
    [string]$Dir = "",
    [string]$Out = "",
    [string]$Lang = "zh-Hans-CN",
    [switch]$Serve
)

$ErrorActionPreference = "Continue"
$sb = New-Object System.Text.StringBuilder
$t0 = Get-Date

# ==================== 服务模式（-Serve）：常驻，stdin 收目录、stdout 回结果 ====================
# 协议（每行一条，UTF-8）：
#   收：<目录绝对路径>
#   发：BEGIN /（与单次模式相同的 FILE=/LINE= 行）/ END
# 目的：把 PowerShell 冷启动 + WinRT 引擎初始化的 ~0.45s 固定开销摊销到多次识别上。
# 启动失败/读不到 stdin 就退出，Java 侧会自动回退到「每次冷启」的老路子。
if ($Serve) {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
    $stdin = [Console]::In
    $stdout = [Console]::Out

    try {
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
            })[0]
        function Await($WinRtTask, $ResultType) {
            $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
            $netTask = $asTask.Invoke($null, @($WinRtTask))
            $netTask.Wait(-1) | Out-Null
            $netTask.Result
        }
        [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
        [Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
        [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
        [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
        [Windows.Storage.FileAccessMode, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null

        $engine = $null
        if ($Lang -ne "" -and $Lang -ne "auto") {
            try { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($Lang)) } catch { }
        }
        if ($null -eq $engine) {
            $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
        }
        if ($null -eq $engine) {
            $stdout.WriteLine("FATAL=本机没有可用的 OCR 语言包")
            $stdout.Flush()
            exit 0
        }
        $stdout.WriteLine("READY")
        $stdout.Flush()

        while ($true) {
            $line = $stdin.ReadLine()
            if ($null -eq $line) { break }          # Java 关掉管道 → 正常退出
            $jobDir = $line.Trim()
            if ($jobDir -eq "") { continue }
            $stdout.WriteLine("BEGIN")
            try {
                $files = @(Get-ChildItem -Path $jobDir -Filter *.png | Sort-Object Name)
                foreach ($f in $files) {
                    $ts = Get-Date
                    try {
                        $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($f.FullName)) ([Windows.Storage.StorageFile])
                        $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
                        $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
                        $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
                        $res = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
                        $w = $decoder.PixelWidth
                        $h = $decoder.PixelHeight
                        $stream.Dispose()

                        $stdout.WriteLine("FILE=" + $f.Name + "|W=" + $w + "|H=" + $h + "|MS=" + [int]((Get-Date) - $ts).TotalMilliseconds)
                        foreach ($ln in $res.Lines) {
                            $x0 = 999999; $y0 = 999999; $x1 = -1; $y1 = -1
                            $txt = ""
                            foreach ($wd in $ln.Words) {
                                $b = $wd.BoundingRect
                                if ($b.X -lt $x0) { $x0 = [int]$b.X }
                                if ($b.Y -lt $y0) { $y0 = [int]$b.Y }
                                if (($b.X + $b.Width) -gt $x1) { $x1 = [int]($b.X + $b.Width) }
                                if (($b.Y + $b.Height) -gt $y1) { $y1 = [int]($b.Y + $b.Height) }
                                $txt = $txt + $wd.Text
                            }
                            if ($x1 -lt 0) { continue }
                            if ($txt.Trim() -eq "") { continue }
                            $stdout.WriteLine("LINE=" + $x0 + "," + $y0 + "," + $x1 + "," + $y1 + "|" + $txt)
                        }
                    }
                    catch {
                        $stdout.WriteLine("FILE=" + $f.Name + "|ERR=" + $_.Exception.Message)
                    }
                }
            }
            catch {
                $stdout.WriteLine("ERROR=" + $_.Exception.Message)
            }
            $stdout.WriteLine("END")
            $stdout.Flush()
        }
    }
    catch {
        try { $stdout.WriteLine("FATAL=" + $_.Exception.Message); $stdout.Flush() } catch { }
    }
    exit 0
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime

    # WinRT 的异步 API 在 PS 5.1 里没法直接 await，这里用反射拿 AsTask 泛型方法手动同步等待
    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        })[0]

    function Await($WinRtTask, $ResultType) {
        $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
        $netTask = $asTask.Invoke($null, @($WinRtTask))
        $netTask.Wait(-1) | Out-Null
        $netTask.Result
    }

    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Storage.FileAccessMode, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null

    $engine = $null
    if ($Lang -ne "" -and $Lang -ne "auto") {
        try { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($Lang)) } catch { }
    }
    if ($null -eq $engine) {
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    }
    if ($null -eq $engine) {
        [void]$sb.AppendLine("FATAL=本机没有可用的 OCR 语言包（试过 " + $Lang + " 与当前用户语言）")
        $sb.ToString() | Out-File -FilePath $Out -Encoding utf8
        exit 0
    }
    [void]$sb.AppendLine("T_ENGINE_MS=" + [int]((Get-Date) - $t0).TotalMilliseconds)

    $files = @(Get-ChildItem -Path $Dir -Filter *.png | Sort-Object Name)
    foreach ($f in $files) {
        $ts = Get-Date
        try {
            $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($f.FullName)) ([Windows.Storage.StorageFile])
            $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
            $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
            $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
            $res = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            $w = $decoder.PixelWidth
            $h = $decoder.PixelHeight
            $stream.Dispose()

            [void]$sb.AppendLine("FILE=" + $f.Name + "|W=" + $w + "|H=" + $h + "|MS=" + [int]((Get-Date) - $ts).TotalMilliseconds)
            foreach ($ln in $res.Lines) {
                $x0 = 999999; $y0 = 999999; $x1 = -1; $y1 = -1
                $txt = ""
                foreach ($wd in $ln.Words) {
                    $b = $wd.BoundingRect
                    if ($b.X -lt $x0) { $x0 = [int]$b.X }
                    if ($b.Y -lt $y0) { $y0 = [int]$b.Y }
                    if (($b.X + $b.Width) -gt $x1) { $x1 = [int]($b.X + $b.Width) }
                    if (($b.Y + $b.Height) -gt $y1) { $y1 = [int]($b.Y + $b.Height) }
                    $txt = $txt + $wd.Text
                }
                if ($x1 -lt 0) { continue }
                if ($txt.Trim() -eq "") { continue }
                [void]$sb.AppendLine("LINE=" + $x0 + "," + $y0 + "," + $x1 + "," + $y1 + "|" + $txt)
            }
        }
        catch {
            [void]$sb.AppendLine("FILE=" + $f.Name + "|ERR=" + $_.Exception.Message)
        }
    }
    [void]$sb.AppendLine("T_TOTAL_MS=" + [int]((Get-Date) - $t0).TotalMilliseconds)
}
catch {
    [void]$sb.AppendLine("FATAL=" + $_.Exception.Message)
}

$sb.ToString() | Out-File -FilePath $Out -Encoding utf8
