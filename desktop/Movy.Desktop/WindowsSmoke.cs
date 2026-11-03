using System.IO;
using System.Net;
using System.Net.Http;
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
            window.ShowFixture();await Task.Delay(500);window.UpdateLayout();
            if(window.ActualWidth<=0||window.ActualHeight<=0||window.Background is not SolidColorBrush background||background.Color!=Color.FromRgb(17,20,22))throw new InvalidOperationException("Native window dimensions/theme were not initialized.");
            var bitmap=new RenderTargetBitmap((int)window.ActualWidth,(int)window.ActualHeight,96,96,PixelFormats.Pbgra32);bitmap.Render(window);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using(var file=File.Create(Path.Combine(output,"windows-home-fixture.png")))encoder.Save(file);
            // Exercise the real artwork loader with a relative route and a
            // deterministic image response, including cached-title/raw-detail
            // paths that do not pass through Title.From. Never fetch live media.
            var imageEncoder=new PngBitmapEncoder();imageEncoder.Frames.Add(BitmapFrame.Create(BitmapSource.Create(1,1,96,96,PixelFormats.Bgra32,null,new byte[]{54,174,233,255},4)));
            using var encoded=new MemoryStream();imageEncoder.Save(encoded);var imageHandler=new ArtworkHandler(encoded.ToArray());using var imageClient=new HttpClient(imageHandler);
            var artwork=Ui.Artwork("/img/w342/fixture.jpg/q50.webp",32,32,client:imageClient);var nativeRoot=(System.Windows.Controls.Grid)window.Content;nativeRoot.Children.Add(artwork);
            try{for(int attempt=0;attempt<30&&artwork.Source==null;attempt++)await Task.Delay(100);if(artwork.Source is not BitmapSource decoded||decoded.PixelWidth<=0||imageHandler.Address?.AbsoluteUri!=MovyConfig.Site+"/img/w342/fixture.jpg/q50.webp")throw new InvalidOperationException("Relative artwork did not load/decode through the native image loader.");}
            finally{nativeRoot.Children.Remove(artwork);}
            var account=await window.Connections.Account();if(account.Get("signedIn").ValueKind!=JsonValueKind.False)throw new InvalidOperationException("Guest account fixture failed.");
            if(!MpvRuntime.Installed)await MpvRuntime.Install(_=>{},CancellationToken.None);using var player=new MpvClient();await player.Start(0);var version=await player.Get("mpv-version");if(!version.ToString().Contains("0.41.0"))throw new InvalidOperationException("Wrong playback runtime.");
            string wav=Path.Combine(output,"silence-fixture.wav");using(var writer=new BinaryWriter(File.Create(wav))){writer.Write(Encoding.ASCII.GetBytes("RIFF"));writer.Write(36+16000);writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));writer.Write(16);writer.Write((short)1);writer.Write((short)1);writer.Write(8000);writer.Write(16000);writer.Write((short)2);writer.Write((short)16);writer.Write(Encoding.ASCII.GetBytes("data"));writer.Write(16000);writer.Write(new byte[16000]);}
            await player.Command("loadfile",wav,"replace",-1,new Dictionary<string,string>{{"pause","yes"}});await Task.Delay(1000);var tracks=await player.Get("track-list");if(!tracks.Array().Any(t=>t.Text("type")=="audio"))throw new InvalidOperationException("Generated audio fixture did not load.");
            await player.Set("audio-delay",.2);await player.Set("sub-delay",-.5);await player.Set("speed",1.25);await player.Command("seek",.25,"absolute+exact");
            if(Math.Abs((await player.Get("audio-delay")).GetDouble()-.2)>.001||Math.Abs((await player.Get("sub-delay")).GetDouble()+.5)>.001||(await player.Get("pause")).ValueKind!=JsonValueKind.True)throw new InvalidOperationException("Timing/pause checks failed.");
            await File.WriteAllTextAsync(Path.Combine(output,"result.txt"),"PASS WPF native layout, relative artwork HTTP request and bitmap decoding, hidden origin-scoped WebView2 guest account, verified mpv runtime, named-pipe commands, generated WAV playback, audio/subtitle delay, seek and pause.\nArtwork/audio responses are fixtures; no live media or signed-in account was tested.\n");Application.Current.Shutdown(0);
        }catch(Exception error){await File.WriteAllTextAsync(Path.Combine(output,"result.txt"),"FAIL "+error.GetType().Name+"\n"+error.Message+"\n");Application.Current.Shutdown(1);}
    }
    private sealed class ArtworkHandler(byte[] bytes):HttpMessageHandler
    {
        public Uri? Address{get;private set;}
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct){Address=request.RequestUri;var response=new HttpResponseMessage(HttpStatusCode.OK){Content=new ByteArrayContent(bytes)};response.Content.Headers.ContentType=new System.Net.Http.Headers.MediaTypeHeaderValue("image/png");return Task.FromResult(response);}
    }
}
