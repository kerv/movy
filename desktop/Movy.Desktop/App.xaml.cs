using System.IO;
using System.Windows;

namespace Movy.Desktop;
public partial class App : Application
{
    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MovyDesktop");
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        bool smoke=e.Args.Contains("--smoke-test"),reported=false;
        DispatcherUnhandledException += (_, args) => {
            args.Handled=true;string report=Diagnostics.Report(args.Exception);
            if(smoke){Shutdown(1);return;}
            if(reported)return;reported=true;
            MessageBox.Show("Movy encountered a desktop error ("+args.Exception.GetType().Name+", 0x"+args.Exception.HResult.ToString("X8")+").\n\nA safe diagnostic report was saved to:\n"+report+"\n\nThis report excludes credentials and stream URLs.","Movy Desktop");
        };
        Directory.CreateDirectory(DataDirectory);
        var window=new MainWindow();MainWindow=window;
        if(e.Args.Contains("--smoke-test"))window.Loaded+=async(_,_)=>await WindowsSmoke.Run(window);
        MainWindow.Show();
    }
}
