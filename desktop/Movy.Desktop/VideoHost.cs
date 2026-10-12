using System.Runtime.InteropServices;
using System.Windows.Interop;
namespace Movy.Desktop;
public sealed class VideoHost : HwndHost
{
    public nint VideoHandle { get; private set; }
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern nint CreateWindowEx(int exStyle,string className,string title,int style,int x,int y,int width,int height,nint parent,nint menu,nint instance,nint param);
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool DestroyWindow(nint hwnd);
    protected override HandleRef BuildWindowCore(HandleRef parent)
    {
        VideoHandle=CreateWindowEx(0,"STATIC","",0x40000000|0x10000000|0x02000000|0x00000004,0,0,1,1,parent.Handle,0,0,0);
        if (VideoHandle==0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        return new HandleRef(this,VideoHandle);
    }
    protected override void DestroyWindowCore(HandleRef hwnd) { DestroyWindow(hwnd.Handle); VideoHandle=0; }
}
