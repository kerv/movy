using System.IO;
using System.Windows;

namespace Movy.Desktop;
public partial class App : Application
{
    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MovyDesktop");
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // Do not log exception messages: requests can contain private media URLs.
        DispatcherUnhandledException += (_, args) => { args.Handled = true; MessageBox.Show("Movy could not complete that action. Please retry or restart the app.", "Movy Desktop"); };
        Directory.CreateDirectory(DataDirectory);
        var window=new MainWindow();MainWindow=window;
        if(e.Args.Contains("--smoke-test"))window.Loaded+=async(_,_)=>await WindowsSmoke.Run(window);
        MainWindow.Show();
    }
}
