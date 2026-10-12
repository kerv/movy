using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Movy.Core;

namespace Movy.Desktop;
internal static class WindowsSmoke
{
    public static async Task Run(MainWindow window)
    {
        string output=Environment.GetEnvironmentVariable("MOVY_SMOKE_OUTPUT")??Path.Combine(App.DataDirectory,"Smoke");Directory.CreateDirectory(output);
        try{
            window.ShowFixture();await Task.Delay(500);window.UpdateLayout();var bitmap=new RenderTargetBitmap((int)window.ActualWidth,(int)window.ActualHeight,96,96,PixelFormats.Pbgra32);bitmap.Render(window);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using(var file=File.Create(Path.Combine(output,"windows-home-fixture.png")))encoder.Save(file);
            var account=await window.Connections.Account();if(account.Get("signedIn").ValueKind!=JsonValueKind.False)throw new InvalidOperationException("Guest account fixture failed.");
            if(!MpvRuntime.Installed)await MpvRuntime.Install(_=>{},CancellationToken.None);using var player=new MpvClient();await player.Start(0);var version=await player.Get("mpv-version");if(!version.ToString().Contains("0.41.0"))throw new InvalidOperationException("Wrong playback runtime.");
            string wav=Path.Combine(output,"silence-fixture.wav");using(var writer=new BinaryWriter(File.Create(wav))){writer.Write(Encoding.ASCII.GetBytes("RIFF"));writer.Write(36+16000);writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));writer.Write(16);writer.Write((short)1);writer.Write((short)1);writer.Write(8000);writer.Write(16000);writer.Write((short)2);writer.Write((short)16);writer.Write(Encoding.ASCII.GetBytes("data"));writer.Write(16000);writer.Write(new byte[16000]);}
            await player.Command("loadfile",wav,"replace",-1,new Dictionary<string,string>{{"pause","yes"}});await Task.Delay(1000);var tracks=await player.Get("track-list");if(!tracks.Array().Any(t=>t.Text("type")=="audio"))throw new InvalidOperationException("Generated audio fixture did not load.");
            await player.Set("audio-delay",.2);await player.Set("sub-delay",-.5);await player.Set("speed",1.25);await player.Command("seek",.25,"absolute+exact");
            if(Math.Abs((await player.Get("audio-delay")).GetDouble()-.2)>.001||Math.Abs((await player.Get("sub-delay")).GetDouble()+.5)>.001||(await player.Get("pause")).ValueKind!=JsonValueKind.True)throw new InvalidOperationException("Timing/pause checks failed.");
            await File.WriteAllTextAsync(Path.Combine(output,"result.txt"),"PASS WPF native layout, hidden origin-scoped WebView2 guest account, verified mpv runtime, named-pipe commands, generated WAV playback, audio/subtitle delay, seek and pause.\nNo live media or signed-in account was tested.\n");Application.Current.Shutdown(0);
        }catch(Exception error){await File.WriteAllTextAsync(Path.Combine(output,"result.txt"),"FAIL "+error.GetType().Name+"\n"+error.Message+"\n");Application.Current.Shutdown(1);}
    }
}
