# DHU 창(차량 화면·계기판)을 PNG로: dhu_0.png, dhu_1.png
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System; using System.Collections.Generic; using System.Runtime.InteropServices; using System.Text;
public class DW { public delegate bool P(IntPtr h, IntPtr l);
[DllImport("user32.dll")] public static extern bool EnumWindows(P p, IntPtr l);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
[DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
public struct R { public int L, T, Rt, B; }
public static List<IntPtr> Of(uint pid) { var r = new List<IntPtr>(); EnumWindows((h,l)=>{ uint p; GetWindowThreadProcessId(h, out p); if (p==pid && IsWindowVisible(h)) r.Add(h); return true; }, IntPtr.Zero); return r; } }
'@
$out = if ($args.Count -gt 0) { $args[0] } else { Join-Path $env:TEMP "dayzhud" }
New-Item -ItemType Directory -Force $out | Out-Null
$i = 0
foreach ($proc in Get-Process desktop-head-unit -ErrorAction SilentlyContinue) {
  foreach ($h in [DW]::Of([uint32]$proc.Id)) {
    $sb = New-Object System.Text.StringBuilder 256; [void][DW]::GetWindowText($h, $sb, 256)
    $r = New-Object DW+R; [void][DW]::GetWindowRect($h, [ref]$r)
    $w = $r.Rt - $r.L; $hh = $r.B - $r.T
    if ($w -lt 50) { continue }
    $bmp = New-Object System.Drawing.Bitmap $w, $hh; $g = [System.Drawing.Graphics]::FromImage($bmp)
    $dc = $g.GetHdc(); [void][DW]::PrintWindow($h, $dc, 2); $g.ReleaseHdc($dc)
    $f = Join-Path $out "dhu_$i.png"; $bmp.Save($f); $i++
    "$($sb.ToString()) ${w}x${hh} -> $f"
  }
}
