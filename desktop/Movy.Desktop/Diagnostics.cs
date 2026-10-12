using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

namespace Movy.Desktop;
internal static class Diagnostics
{
    public static string Report(Exception error)
    {
        var report=new StringBuilder();report.AppendLine(DateTimeOffset.UtcNow.ToString("O"));report.AppendLine("Movy "+Assembly.GetExecutingAssembly().GetName().Version);report.AppendLine(RuntimeInformation.OSDescription);report.AppendLine(RuntimeInformation.FrameworkDescription);
        // Types, HRESULTs and method names only: NEVER exception messages,
        // arguments, browser state, user IDs, credentials or request URLs.
        int depth=0;for(Exception? current=error;current!=null&&depth++<8;current=current.InnerException){report.AppendLine(current.GetType().FullName+" · HRESULT 0x"+current.HResult.ToString("X8"));foreach(var frame in new StackTrace(current,true).GetFrames().Take(30)){var method=frame.GetMethod();report.AppendLine("  "+method?.DeclaringType?.FullName+"."+method?.Name+" · line "+frame.GetFileLineNumber());}}
        string directory=Environment.GetEnvironmentVariable("MOVY_SMOKE_OUTPUT")??Path.Combine(App.DataDirectory,"Diagnostics");string file=Path.Combine(directory,"desktop-error.log");
        try{Directory.CreateDirectory(directory);File.AppendAllText(file,report+Environment.NewLine);}catch(IOException){}catch(UnauthorizedAccessException){}
        return file;
    }
}
