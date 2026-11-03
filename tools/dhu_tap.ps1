# DHU 차량 화면 창을 마우스로 클릭: dhu_tap.ps1 x y  (dhu_0.png 좌표 = 창 왼쪽 위 기준, 제목줄 포함). 끌기: dhu_tap.ps1 x y x2 y2
# 폰 쪽 adb input이 막혀 있어도(샤오미 "USB 디버깅(보안 설정)") 차량 화면은 이걸로 누를 수 있다. 마우스 커서가 실제로 움직인다
# DHU 창이 맨 앞이 아니면(다른 창에 가려짐) 누르지 않고 끝낸다. 이 파일은 BOM 있는 UTF-8이어야 한다 (Windows PowerShell 5)
# -n 2: 두 번 누름 (AA는 몇 초 지나면 지도 버튼을 숨기고, 첫 터치는 다시 보이게만 함. 스크립트 시작이 느려서 따로 부르면 그새 또 숨음)
param([int]$x, [int]$y, [int]$x2 = -1, [int]$y2 = -1, [int]$n = 1)
Add-Type @'
using System; using System.Runtime.InteropServices;
public class DT { public struct R { public int L, T, Rt, B; } public struct P { public int X, Y; }
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
[DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(P p);
[DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint f);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint f, int x, int y, uint d, IntPtr e);
[DllImport("user32.dll")] public static extern void keybd_event(byte k, byte s, uint f, IntPtr e);
[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
public delegate bool E(IntPtr h, IntPtr l);
[DllImport("user32.dll")] public static extern bool EnumWindows(E e, IntPtr l);
[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
// DHU는 창이 둘(차량 화면·계기판)이라 제목으로 찾는다
public static IntPtr Find() { IntPtr r = IntPtr.Zero; EnumWindows((h,l)=>{ var sb = new System.Text.StringBuilder(256); GetWindowText(h, sb, 256); if (sb.ToString().StartsWith("Android Auto - Desktop Head Unit")) { r = h; return false; } return true; }, IntPtr.Zero); return r; } }
'@
$h = [DT]::Find()
if ($h -eq [IntPtr]::Zero) { "no DHU window"; exit 1 }
# 배율 적용 전 창 크기(= dhu_shot 캡처 크기)를 먼저 재고, 그다음 실제 픽셀로 전환
$lr = New-Object DT+R; [void][DT]::GetWindowRect($h, [ref]$lr)
[void][DT]::SetProcessDPIAware()
# 백그라운드 프로세스는 SetForegroundWindow가 막힘 → Alt 한 번 눌렀다 떼면 허용됨
[DT]::keybd_event(0x12, 0, 0, [IntPtr]::Zero); [DT]::keybd_event(0x12, 0, 2, [IntPtr]::Zero)
[void][DT]::ShowWindow($h, 9); [void][DT]::SetForegroundWindow($h); Start-Sleep -Milliseconds 400
$r = New-Object DT+R; [void][DT]::GetWindowRect($h, [ref]$r)
$s = ($r.Rt - $r.L) / ($lr.Rt - $lr.L) # 화면 배율(125% 등)
$p = New-Object DT+P; $p.X = $r.L + [int]($x * $s); $p.Y = $r.T + [int]($y * $s)
if ([DT]::GetForegroundWindow() -ne $h -or [DT]::GetAncestor([DT]::WindowFromPoint($p), 2) -ne $h) { "DHU window not on top - not clicking"; exit 2 }
[void][DT]::SetCursorPos($p.X, $p.Y); Start-Sleep -Milliseconds 100
for ($k = 1; $k -lt $n; $k++) { [DT]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 80; [DT]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 800 }
[DT]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 80
if ($x2 -ge 0) { # 누른 채 20단계로 옮긴 뒤 뗌
  for ($i = 1; $i -le 20; $i++) { [void][DT]::SetCursorPos($p.X + [int](($x2 - $x) * $s * $i / 20), $p.Y + [int](($y2 - $y) * $s * $i / 20)); Start-Sleep -Milliseconds 20 }
}
[DT]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
if ($x2 -ge 0) { "drag " + $x + "," + $y + " -> " + $x2 + "," + $y2 } else { "tap " + $x + "," + $y }
