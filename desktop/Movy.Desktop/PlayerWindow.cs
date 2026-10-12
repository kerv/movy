using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Movy.Core;

namespace Movy.Desktop;
public sealed class PlayerWindow : Window
{
    private readonly Title title;
    private ResolvedStream stream;
    private readonly PlaybackStore store;
    private readonly Catalog catalog;
    private readonly BrowserConnections connections;
    private readonly MpvClient player=new();
    private readonly VideoHost video=new();
    private readonly TextBlock status=Ui.Text("Starting native player…",14,Ui.Muted);
    private readonly TextBlock time=Ui.Text("0:00 / 0:00",13,Ui.Muted);
    private readonly Slider timeline=new() { Minimum=0,Maximum=1,Margin=new Thickness(16,0,16,0) };
    private readonly Slider volume=new() { Minimum=0,Maximum=100,Value=80,Width=100,Margin=new Thickness(8,0,20,0) };
    private readonly DispatcherTimer timer=new() { Interval=TimeSpan.FromSeconds(1) };
    private readonly CancellationTokenSource lifetime=new();
    private readonly TaskCompletionSource completion=new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Dictionary<string,string> captionFiles=[];
    private IReadOnlyList<Caption>? captions;
    private Caption? selectedCaption;
    private bool captionsOff=true;
    private double position,duration,speed=1,audioDelay,subtitleDelay;
    private bool paused,updating,seeking,ready,changing,closed;
    private int checkpoints;
    private DateTime exitArmed;
    private WindowState beforeFullscreen;
    private bool fullscreen;
    public Task Completion=>completion.Task;
    public Title? NextTitle { get; private set; }
    public PlayerWindow(Title title,ResolvedStream stream,double position,PlaybackStore store,Catalog catalog,BrowserConnections connections)
    {
        this.title=title;this.stream=stream;this.position=position;this.store=store;this.catalog=catalog;this.connections=connections;Title="Movy · "+title.Name+(title.EpisodeLabel.Length>0?" · "+title.EpisodeLabel:"");Width=1280;Height=820;MinWidth=860;MinHeight=540;Background=Brushes.Black;
        var root=new Grid();root.RowDefinitions.Add(new RowDefinition());root.RowDefinitions.Add(new RowDefinition { Height=GridLength.Auto });root.Children.Add(video);Content=root;
        var controls=Ui.Column();controls.Background=new SolidColorBrush(Color.FromRgb(17,20,22));controls.Margin=new Thickness(0);Grid.SetRow(controls,1);root.Children.Add(controls);
        var name=Ui.Text(title.Name+(title.EpisodeLabel.Length>0?" · "+title.EpisodeLabel:""),20);name.Margin=new Thickness(20,12,20,8);controls.Children.Add(name);controls.Children.Add(timeline);var row=Ui.Row();row.Margin=new Thickness(20,8,20,0);controls.Children.Add(row);
        row.Children.Add(Ui.AsyncButton("↶ 10",()=>Command("seek",-10,"relative+exact")));row.Children.Add(Ui.AsyncButton("Play / pause",TogglePause));row.Children.Add(Ui.AsyncButton("10 ↷",()=>Command("seek",10,"relative+exact")));row.Children.Add(time);row.Children.Add(volume);row.Children.Add(Ui.AsyncButton("CC",()=>Tracks(true)));row.Children.Add(Ui.AsyncButton("Settings",Settings));row.Children.Add(Ui.Button("Fullscreen",ToggleFullscreen));status.Margin=new Thickness(20,0,20,10);controls.Children.Add(status);
        timeline.PreviewMouseLeftButtonDown+=(_,_)=>seeking=true;timeline.PreviewMouseLeftButtonUp+=async(_,_)=>{seeking=false;if(ready&&!changing)await Command("seek",timeline.Value,"absolute+exact");};timeline.KeyUp+=async(_,e)=>{if(e.Key is Key.Left or Key.Right && ready&&!changing)await Command("seek",timeline.Value,"absolute+exact");};
        volume.ValueChanged+=async(_,_)=>{if(ready&&!updating&&!changing)await Set("volume",volume.Value);};
        player.EventReceived+=message=>Dispatcher.BeginInvoke(()=>{if(closed)return;if(message.Text("event")=="end-file"&&message.Text("reason")=="error"){status.Text="This source could not play. Open Settings → Playback source to try another.";}if(message.Text("event")=="end-file"&&message.Text("reason")=="eof")Save();});
        timer.Tick+=async(_,_)=>await Poll();Loaded+=async(_,_)=>{try{await player.Start(video.VideoHandle);await Load(stream,position,false);ready=true;timer.Start();status.Text="Space: pause · ←/→: seek · F11: fullscreen · Esc twice: return";}catch{status.Text="The native player could not start. Retry playback or another source.";MessageBox.Show(status.Text,"Movy playback");Close();}};
        PreviewKeyDown+=async(_,e)=>{if(e.Key is Key.Escape or Key.BrowserBack){if(e.IsRepeat){e.Handled=true;return;}if((DateTime.UtcNow-exitArmed).TotalSeconds<=2.5)Close();else{exitArmed=DateTime.UtcNow;status.Text="Press Esc again to return to the title";}e.Handled=true;return;}exitArmed=DateTime.MinValue;if(e.Key==Key.F11){ToggleFullscreen();e.Handled=true;}else if(ready&&!changing&&e.Key==Key.Space){await TogglePause();e.Handled=true;}else if(ready&&!changing&&e.Key is Key.Left or Key.Right && e.OriginalSource is not Slider){await Command("seek",e.Key==Key.Left?-10:10,"relative+exact");e.Handled=true;}};
        Closed+=(_,_)=>{closed=true;timer.Stop();Save();lifetime.Cancel();player.Dispose();foreach(string file in captionFiles.Values)try{File.Delete(file);}catch(IOException){}completion.TrySetResult();};
    }
    private async Task Set(string property,object value){try{await player.Set(property,value);}catch{if(!closed)status.Text="That playback option could not be applied. Please retry.";}}
    private async Task Command(params object[] values){try{await player.Command(values);}catch{if(!closed)status.Text="The player could not complete that action.";}}
    private async Task TogglePause(){if(!ready||changing)return;paused=!paused;await Set("pause",paused);}
    private async Task Load(ResolvedStream source,double at,bool pause)
    {
        await player.Load(source,at,pause);await player.Set("speed",speed);await player.Set("audio-delay",audioDelay);await player.Set("sub-delay",subtitleDelay);await player.Set("volume",volume.Value);
        if(selectedCaption!=null&&captionFiles.TryGetValue(selectedCaption.Url,out var file))await player.Command("sub-add",file,"select",selectedCaption.Label,selectedCaption.Language);else if(captionsOff)await player.Set("sid","no");
        position=at;paused=pause;captions=null;
    }
    private async Task Poll()
    {
        if(!ready||changing||updating||closed)return;updating=true;
        try{var p=await player.Get("time-pos");var d=await player.Get("duration");if(closed)return;position=p.ValueKind==JsonValueKind.Number?p.GetDouble():position;duration=d.ValueKind==JsonValueKind.Number?d.GetDouble():duration;time.Text=Ui.Time(position)+" / "+Ui.Time(duration);if(!seeking){timeline.Maximum=Math.Max(1,duration);timeline.Value=Math.Clamp(position,0,timeline.Maximum);}if(++checkpoints%10==0)Save();}catch{if(!closed)status.Text="Waiting for the source… Settings lets you choose another.";}finally{updating=false;}
    }
    private void Save(){if(store.Progress(title,position,duration)&&title.IsSeries)_=QueueNext();}
    private async Task QueueNext(){try{store.QueueNext(title,await catalog.NextEpisode(title));}catch{/* Home can retry read-only next-episode metadata. */}}
    private void ToggleFullscreen(){if(!fullscreen){beforeFullscreen=WindowState;WindowStyle=WindowStyle.None;ResizeMode=ResizeMode.NoResize;WindowState=WindowState.Maximized;}else{WindowStyle=WindowStyle.SingleBorderWindow;ResizeMode=ResizeMode.CanResize;WindowState=beforeFullscreen;}fullscreen=!fullscreen;}
    private async Task Settings()
    {
        if(!ready||changing)return;var labels=new List<string>{"Playback source","Audio language","Subtitles / closed captions","Quality","Playback speed",$"Audio sync · {audioDelay:+0.00;-0.00;0.00} s",$"Subtitle sync · {subtitleDelay:+0.00;-0.00;0.00} s"};if(title.IsSeries)labels.Add("Next episode");
        switch(Ui.Choose(this,"Playback settings",labels)){case 0:await Sources();break;case 1:await Tracks(false);break;case 2:await Tracks(true);break;case 3:await Quality();break;case 4:var rates=new[]{.75,1,1.25,1.5,2};int selected=Ui.Choose(this,"Playback speed",rates.Select(x=>x==1?"Normal":x+"×"));if(selected>=0){speed=rates[selected];await Set("speed",speed);}break;case 5:Sync(false);break;case 6:Sync(true);break;case 7:await Next();break;}
    }
    private async Task Sources(){var names=stream.Servers.Select(s=>(s.Name==stream.Server?"✓ ":"")+s.Label).Append("Refresh current source").ToArray();int selected=Ui.Choose(this,"Playback source",names);if(selected>=0)await ChangeSource(selected<stream.Servers.Count?stream.Servers[selected].Name:stream.Server);}
    private async Task ChangeSource(string server)
    {
        if(changing)return;changing=true;bool wasPaused=paused;var old=stream;double at=position;using var cancel=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);await Set("pause",true);video.Visibility=Visibility.Hidden;
        var panel=Ui.Column();var text=Ui.Text("Finding a stream…",20);panel.Children.Add(text);panel.Children.Add(Ui.Button("Cancel source change",()=>cancel.Cancel()));var dialog=Ui.Dialog(this,"Connecting to "+(server.Length>0?server:"Movy"),panel,540);
        dialog.Closed+=(_,_)=>cancel.Cancel();dialog.Show();
        try{var next=await connections.Resolve(title.Slug,server,message=>text.Text=message,cancel.Token);cancel.Token.ThrowIfCancellationRequested();await Load(next,at,wasPaused);stream=next;status.Text="Source · "+(next.Server.Length>0?next.Server:"Movy");}
        catch(OperationCanceledException){await Set("pause",wasPaused);status.Text="Source change canceled. Previous source kept.";}
        catch{stream=old;try{await Load(old,at,wasPaused);}catch{}status.Text="Source change failed. Tried restoring your previous source and position.";}
        finally{dialog.Close();if(!closed)video.Visibility=Visibility.Visible;changing=false;}
    }
    private async Task Quality()
    {
        if(stream.Sources.Count==0){status.Text="This source has no separately advertised quality choices. Try Playback source instead.";return;}
        int selected=Ui.Choose(this,"Quality",stream.Sources.Select(s=>(s.Url==stream.Url?"✓ ":"")+s.Label));if(selected<0)return;var next=stream with {Url=stream.Sources[selected].Url,Headers=MpvProtocol.HeadersFor(stream,stream.Sources[selected].Url)};bool wasPaused=paused;changing=true;var old=stream;double at=position;
        try{await Load(next,at,wasPaused);stream=next;}catch{try{await Load(old,at,wasPaused);}catch{}status.Text="That quality could not play. Try a different source.";}finally{changing=false;}
    }
    private async Task Tracks(bool subtitles)
    {
        if(!ready||changing)return;JsonElement all;try{all=await player.Get("track-list");}catch{status.Text="Track information is unavailable. Please retry.";return;}
        var tracks=all.Array().Where(t=>t.Text("type")== (subtitles?"sub":"audio")).ToArray();
        if(subtitles&&captions==null){status.Text="Looking up captions on Movy…";try{captions=stream.Captions.Count>0?stream.Captions:await catalog.Captions(title.Slug,lifetime.Token);}catch{captions=[];status.Text="Caption lookup failed. Press CC again to retry.";}}
        string Label(JsonElement t){string name=t.Text("title");if(string.IsNullOrWhiteSpace(name)||name.Trim().All(c=>c=='`')){string language=t.Text("lang");language=language switch{"eng"=>"en","spa"=>"es","fra"=>"fr","deu"=>"de","por"=>"pt",_=>language};try{name=language.Length>0&&language!="und"?System.Globalization.CultureInfo.GetCultureInfo(language).EnglishName:"";}catch(System.Globalization.CultureNotFoundException){name=language;}if(string.IsNullOrWhiteSpace(name))name=subtitles?"Subtitle track":"Original audio";}return (t.Get("selected").ValueKind==JsonValueKind.True?"✓ ":"")+name;}
        var labels=new List<string>{subtitles?"Off":"Auto / original audio"};labels.AddRange(tracks.Select(Label));if(subtitles){labels.AddRange((captions??[]).Select(c=>"Movy · "+c.Label));labels.Add("Search caption service again");}
        int selected=Ui.Choose(this,subtitles?"Subtitles":"Audio language",labels);if(selected<0)return;
        if(subtitles&&selected==labels.Count-1){captions=null;try{captions=await catalog.Captions(title.Slug,lifetime.Token);}catch{status.Text="Caption service could not be reached. Try again later.";return;}await Tracks(true);return;}
        if(selected==0){if(subtitles){captionsOff=true;selectedCaption=null;}await Set(subtitles?"sid":"aid",subtitles?"no":"auto");}
        else if(selected<=tracks.Length){if(subtitles){captionsOff=false;selectedCaption=null;}await Set(subtitles?"sid":"aid",(int)tracks[selected-1].Number("id"));}
        else if(subtitles)await ExternalCaption(captions![selected-1-tracks.Length]);
    }
    private async Task ExternalCaption(Caption caption)
    {
        try{if(!captionFiles.TryGetValue(caption.Url,out string? file)){
                using var http=new HttpClient {Timeout=TimeSpan.FromSeconds(20)};http.DefaultRequestHeaders.UserAgent.ParseAdd("MovyDesktop/1.20");
                // No media Cookie/Authorization headers are sent to caption hosts.
                using var response=await http.GetAsync(caption.Url,HttpCompletionOption.ResponseHeadersRead,lifetime.Token);response.EnsureSuccessStatusCode();await using var input=await response.Content.ReadAsStreamAsync(lifetime.Token);using var bytes=new MemoryStream();byte[] buffer=new byte[65536];int read;while((read=await input.ReadAsync(buffer,lifetime.Token))>0){if(bytes.Length+read>4*1024*1024)throw new InvalidDataException();bytes.Write(buffer,0,read);}string extension=Encoding.UTF8.GetString(bytes.GetBuffer(),0,(int)Math.Min(bytes.Length,32)).TrimStart('\uFEFF',' ','\r','\n').StartsWith("WEBVTT",StringComparison.Ordinal)?".vtt":".srt";
                string folder=Path.Combine(App.DataDirectory,"Captions");Directory.CreateDirectory(folder);file=Path.Combine(folder,Guid.NewGuid().ToString("N")+extension);await File.WriteAllBytesAsync(file,bytes.ToArray(),lifetime.Token);captionFiles[caption.Url]=file;
            }
            await player.Command("sub-add",file,"select",caption.Label,caption.Language);captionsOff=false;selectedCaption=caption;status.Text="Captions · "+caption.Label;
        }catch{status.Text="This caption file could not load. Try another release or language.";}
    }
    private void Sync(bool subtitles)
    {
        var body=Ui.Column();var value=Ui.Text("",24,Ui.Gold);body.Children.Add(value);body.Children.Add(Ui.Text("− plays earlier · + plays later\nApplies to this playback only. Reset starts at zero.",14,Ui.Muted));var row=Ui.Row();body.Children.Add(row);void Update()=>value.Text=(subtitles?subtitleDelay:audioDelay).ToString("+0.00;-0.00;0.00",System.Globalization.CultureInfo.InvariantCulture)+" s";Update();
        foreach(var item in new[]{("− 0.10 s",-.1),("Reset",0.0),("+ 0.10 s",.1)}){var step=item;row.Children.Add(Ui.AsyncButton(step.Item1,async()=>{double next=step.Item2==0?0:(subtitles?subtitleDelay:audioDelay)+step.Item2;next=Math.Clamp(next,subtitles?-10:-2,subtitles?10:2);if(subtitles)subtitleDelay=next;else audioDelay=next;await Set(subtitles?"sub-delay":"audio-delay",next);Update();}));}
        var dialog=Ui.Dialog(this,subtitles?"Subtitle timing":"Audio timing",body);body.Children.Add(Ui.Button("Done",()=>dialog.Close()));dialog.ShowDialog();
    }
    private async Task Next(){try{var next=await catalog.NextEpisode(title,lifetime.Token);if(next==null||!PlaybackStore.Aired(next)){status.Text="No next episode is currently released.";return;}NextTitle=next;Close();}catch{status.Text="Next-episode metadata could not load. Try the title's season list.";}}
}
