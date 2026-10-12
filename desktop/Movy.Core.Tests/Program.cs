using System.Net;
using System.Text.Json;
using Movy.Core;

static void Check(bool condition,string label){if(!condition)throw new Exception(label);Console.WriteLine("PASS "+label);}
using var fixture=JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","catalog.json")));
var rows=Catalog.Rows(fixture.RootElement);Check(rows.Count==2&&rows[0].Titles.Count==2&&rows[1].Name=="Popular on Netflix","shared Movy home fixture parsed into named native rails");
var extracted=Catalog.NextData("<html><script id='__NEXT_DATA__' type='application/json'>"+fixture.RootElement.GetRawText()+"</script></html>");Check(Catalog.Rows(extracted).Count==2,"SSR next-data parser accepts attribute ordering and quotes");
Check(Provider.All.Length==7&&Provider.All.Single(p=>p.Name=="Disney+").WatchId==337&&MovyConfig.Site=="https://www.movy.sx","providers and backend mappings load from shared configuration");
string url=Catalog.LibraryUrl("tv",Provider.All[3],18,Sort.Alphabetical,2,"2026-10-12");Check(url.Contains("with_networks=2739")&&url.Contains("with_genres=18")&&url.Contains("original_name.asc")&&url.Contains("first_air_date.lte=2026-10-12")&&url.Contains("page=2"),"series library filters, sorting, pagination and released-date constraints");
Check(Catalog.LibraryUrl("movie",Provider.All[0],0,Sort.Rating,1,"2026-10-12").Contains("vote_count.gte=100")&&Catalog.LibraryUrl("movie",Provider.All[0],0,Sort.Popular,1,"2026-10-12").Contains("watch_region=US"),"movie provider region and rating vote threshold");
Check(!Security.TitlePath("/movie/1?redirect=evil")&&!Security.Https("http://media.example/a")&&!Security.Https("https://user:password@media.example/a")&&!Security.Site("https://movy.sx.evil.example")&&Security.RootKey("/tv/1399/2/3")=="/tv/1399","title and origin restrictions reject unsafe paths and URLs");
Check(Catalog.CaptionEndpoint("/tv/1399/2/3").EndsWith("id=1399&season=2&episode=3"),"captions use the exact series episode");
var stream=new ResolvedStream("https://media.example/a.m3u8",new Dictionary<string,string>{{"Cookie","fixture"},{"Origin",MovyConfig.Site},{"Authorization","never-forward"},{"User-Agent","line\r\ninjection"}},[],[],[],"");
Check(!MpvProtocol.HeadersFor(stream,"https://other.example/b.m3u8").ContainsKey("Cookie")&&!MpvProtocol.HeadersFor(stream,stream.Url).ContainsKey("Authorization")&&!MpvProtocol.HeadersFor(stream,stream.Url).ContainsKey("User-Agent"),"playback headers do not leak auth or cross-host cookies and reject CRLF");
using(var load=JsonDocument.Parse(MpvProtocol.Load(7,stream.Url,91.234,true))){var command=load.RootElement.Get("command");Check(command[3].GetInt32()==-1&&command[4].Text("pause")=="yes"&&command[4].Text("start")=="91.234","mpv load command preserves position/pause with the modern index argument");}
string directory=Path.Combine(Path.GetTempPath(),"movy-core-tests-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
try {
    var store=new PlaybackStore(Path.Combine(directory,"library.json"));var series=new Title(1399,"/tv/1399/2/3","Series");var movie=rows[0].Titles[0];store.Progress(series,123,3000);store.ToggleSaved(movie);
    Check(store.Continue().Single().EpisodeLabel=="S2 E3"&&store.Position(series.Slug)==123,"local resume position and episode metadata");
    store.Dismiss(series,"account:one:1");Check(store.Continue(scope:"account:one:1").Count==0&&store.Continue(scope:"account:one:2").Count==1&&store.Recent.Count==1,"Continue Watching dismissal is profile-scoped and retains history");
    Check(store.Progress(series,2999,3000),"first completion triggers next lookup");var next=series with{Slug="/tv/1399/2/4",NextEpisode=true,AvailableOn="2020-01-01"};store.QueueNext(series,next);store.Progress(series,3000,3000);
    Check(store.Continue().Single().NextEpisode,"repeated completion checkpoints retain the queued next episode");
    store.Progress(next,50,3000);store.QueueNext(series,series with{Slug="/tv/1399/3/1",NextEpisode=true,AvailableOn="2020-01-01"});Check(store.Continue().Single().Slug==next.Slug&&!store.Continue().Single().NextEpisode,"stale async next lookup cannot overwrite newer episode progress");
    Check(!PlaybackStore.Aired(next with{AvailableOn="2999-01-01"}),"future episodes are not offered as released");
    for(int i=1;i<=55;i++)store.Progress(new Title(i,"/movie/"+i,"Fixture "+i),100,7000);
    var reloaded=new PlaybackStore(Path.Combine(directory,"library.json"));Check(reloaded.Recent.Count==40&&reloaded.Saved.Count==1,"bounded history and saved list survive disk reload");
} finally {Directory.Delete(directory,true);}
var catalog=new Catalog(new HttpClient(new FixtureHandler()));var after=new Title(1,"/tv/1/1/10","Series");var candidate=await catalog.NextEpisode(after);Check(candidate?.Slug=="/tv/1/2/1"&&candidate.NextEpisode,"next episode validates actual metadata and crosses season boundaries");
Console.WriteLine("Desktop core checks completed.");

sealed class FixtureHandler:HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){Content=new StringContent(request.RequestUri!.AbsolutePath.EndsWith("/season/1")?"{\"episodes\":[{\"episode_number\":10,\"air_date\":\"2020-01-01\"}]}":"{\"episodes\":[{\"episode_number\":1,\"air_date\":\"2020-01-02\"}]}")});
}
