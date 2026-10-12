using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Movy.Core;

namespace Movy.Desktop;
public sealed class MainWindow : Window
{
    private readonly Grid root=new();
    private readonly ScrollViewer scroll=new() { VerticalScrollBarVisibility=ScrollBarVisibility.Auto, HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled };
    private readonly StackPanel navigation=new();
    private readonly Stack<PageState> history=new();
    private readonly Catalog catalog=new();
    private readonly PlaybackStore store=new(System.IO.Path.Combine(App.DataDirectory,"library.json"));
    private readonly BrowserConnections connections;
    private readonly CancellationTokenSource lifetime=new();
    private StackPanel body=new();
    private string pageName="Home";
    private int generation;
    private IReadOnlyList<Row>? home;
    private DateTime homeLoaded;
    private JsonElement account;
    private readonly Dictionary<string,Title?> accountNext=[];
    private readonly Dictionary<string,DateTime> accountNextLoaded=[];
    private record PageState(StackPanel Body,string Name,double Scroll,IInputElement? Focus);
    private string AccountScope=>account.Get("signedIn").ValueKind==JsonValueKind.True?"account:"+account.Text("username")+":"+account.Get("profile").Text("id"):"device";
    public MainWindow()
    {
        Title="Movy Desktop";Width=1380;Height=860;MinWidth=900;MinHeight=620;
        // Implicit Window styles do not automatically theme derived Window types.
        Background=new SolidColorBrush(Color.FromRgb(17,20,22));Foreground=Brushes.White;root.Background=Background;
        root.ColumnDefinitions.Add(new ColumnDefinition { Width=new GridLength(218) });root.ColumnDefinitions.Add(new ColumnDefinition());
        var sidebar=new ScrollViewer { Content=navigation,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,Background=new SolidColorBrush(Color.FromRgb(15,18,20)) };navigation.Margin=new Thickness(16,24,6,16);root.Children.Add(sidebar);
        navigation.Children.Add(Ui.Heading("MOVY",26));Nav("Home",()=>ShowHome());Nav("Search",()=>ShowSearch());Nav("My List",()=>ShowSaved());Nav("Account",()=>ShowAccount());navigation.Children.Add(Ui.Heading("LIBRARIES",12));
        Nav("Movies",()=>ShowRecommended("movie"));Nav("Series",()=>ShowRecommended("tv"));
        foreach(var provider in Provider.All){var captured=provider;Nav(provider.Name,()=>ShowProvider(captured));}
        navigation.Children.Add(Ui.Text("Windows preview · v"+App.Version+"\nMovy catalog · native playback",12,Ui.Muted));
        Grid.SetColumn(scroll,1);root.Children.Add(scroll);Content=root;connections=new BrowserConnections(root);
        PreviewKeyDown+=(_,e)=>{if(e.Key is Key.BrowserBack || e.Key==Key.Escape){Back();e.Handled=true;}};
        Closed+=(_,_)=>{lifetime.Cancel();connections.Dispose();};
        Loaded+=async(_,_)=>{if(Environment.GetCommandLineArgs().Contains("--smoke-test"))return;await ShowHome();};
    }
    private void Nav(string label,Func<Task> action){var button=Ui.AsyncButton(label,action);button.HorizontalContentAlignment=HorizontalAlignment.Left;button.Margin=new Thickness(0,0,0,5);navigation.Children.Add(button);}
    private int Begin(string name,bool remember=false)
    {
        if(remember){history.Push(new(body,pageName,scroll.VerticalOffset,Keyboard.FocusedElement));if(history.Count>8){var keep=history.Take(8).Reverse().ToArray();history.Clear();foreach(var page in keep)history.Push(page);}}
        else history.Clear();generation++;pageName=name;body=Ui.Column();body.Margin=new Thickness(32,22,32,40);var top=Ui.Row();if(history.Count>0)top.Children.Add(Ui.Button("‹ Back",Back));top.Children.Add(Ui.Heading(name,28));body.Children.Add(top);scroll.Content=body;scroll.ScrollToTop();Ui.Fade(body);return generation;
    }
    private void Back(){if(history.TryPop(out var state)){generation++;body=state.Body;pageName=state.Name;scroll.Content=body;scroll.ScrollToVerticalOffset(state.Scroll);Dispatcher.BeginInvoke(()=>{if(state.Focus!=null)Keyboard.Focus(state.Focus);scroll.ScrollToVerticalOffset(state.Scroll);});}else if(pageName!="Home")_=ShowHome();}
    private bool Current(int token)=>token==generation&&!lifetime.IsCancellationRequested;
    private static string Failure=>"Could not load Movy's catalog. Check your connection and try again.";
    private void Error(StackPanel target,Func<Task> retry){target.Children.Add(Ui.Text(Failure,15,Ui.Muted));target.Children.Add(Ui.AsyncButton("Retry",retry));}
    private async Task<IReadOnlyList<Row>> HomeRows(){if(home==null||(DateTime.UtcNow-homeLoaded).TotalMinutes>=15){home=await catalog.Home(lifetime.Token);homeLoaded=DateTime.UtcNow;}return home;}
    private Button Card(Title title,bool landscape=false)
    {
        var card=Ui.Column();card.Width=landscape?260:174;card.Children.Add(Ui.Artwork(landscape?(title.Backdrop.Length>0?title.Backdrop:title.Poster):title.Poster,card.Width,landscape?146:250));
        var name=Ui.Text(title.Name,16);name.Margin=new Thickness(0,10,0,8);name.MinHeight=42;card.Children.Add(name);card.Children.Add(Ui.Text(title.Metadata,12,Ui.Muted));
        if(landscape){card.Children.Add(Ui.Text((title.NextEpisode?"Up next ":"Resume ")+title.EpisodeLabel+(!title.NextEpisode?" · "+Ui.Time(title.Resume):""),13,Ui.Gold));if(title.Duration>0)card.Children.Add(new ProgressBar { Height=3,Value=Math.Clamp(title.Resume/title.Duration*100,0,100),Foreground=Ui.Gold });}
        var button=Ui.AsyncButton("",()=>ShowDetails(title));button.Content=card;button.Padding=new Thickness(10);button.VerticalContentAlignment=VerticalAlignment.Top;button.ToolTip=title.Name;
        if(landscape){var menu=new ContextMenu();var remove=new MenuItem { Header="Remove from Continue Watching on this PC" };remove.Click+=(_,_)=>{store.Dismiss(title,AccountScope);_=ShowHome();};menu.Items.Add(remove);button.ContextMenu=menu;}
        return button;
    }
    private void Rail(StackPanel target,string name,IEnumerable<Title> values,bool landscape=false)
    {
        var titles=values.ToArray();if(titles.Length==0)return;target.Children.Add(Ui.Heading(name,22));var cards=new StackPanel { Orientation=Orientation.Horizontal };foreach(var title in titles)cards.Children.Add(Card(title,landscape));
        target.Children.Add(new ScrollViewer { Content=cards,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto,VerticalScrollBarVisibility=ScrollBarVisibility.Disabled,Margin=new Thickness(0,0,0,10) });
    }
    private void Hero(StackPanel target,Title title,JsonElement details=default,bool full=false)
    {
        var hero=new Grid { MinHeight=330,Margin=new Thickness(0,12,0,20),ClipToBounds=true,Background=new SolidColorBrush(Color.FromRgb(25,34,40)) };target.Children.Add(hero);
        string art=details.Text("background",title.Backdrop);var image=Ui.Artwork(art,1100,380,true);image.HorizontalAlignment=HorizontalAlignment.Stretch;hero.Children.Add(image);
        hero.Children.Add(new Border { Background=new LinearGradientBrush(Color.FromArgb(255,17,20,22),Color.FromArgb(80,17,20,22),0) });var info=Ui.Column();info.MaxWidth=630;info.HorizontalAlignment=HorizontalAlignment.Left;info.Margin=new Thickness(28);hero.Children.Add(info);
        info.Children.Add(Ui.Text(full?(title.IsSeries?"SERIES":"MOVIE"):"FEATURED ON MOVY",12,Ui.Gold));info.Children.Add(Ui.Heading(title.Name,32));
        info.Children.Add(Ui.Text(title.Metadata+(details.Text("certification").Length>0?" · "+details.Text("certification"):"")+(details.Number("duration")>0?$" · {details.Number("duration"):0} min":""),15,Ui.Gold));
        info.Children.Add(Ui.Text(details.Text("desc",title.Description),15));var actions=Ui.Row();info.Children.Add(actions);
        if(full){Title resume=store.Continue(AccountContinue(),AccountScope).FirstOrDefault(x=>x.Root==title.Root)??title;actions.Children.Add(Ui.AsyncButton(resume.NextEpisode?"▶ Next episode":resume.Resume>0?"▶ Resume":"▶ Play",()=>Play(resume)));if(resume.Resume>0)actions.Children.Add(Ui.AsyncButton("Start over",()=>Play(resume with { Resume=0 },true)));var save=Ui.Button(store.IsSaved(title)?"✓ My List":"+ My List",()=>{});save.Click+=(_,_)=>{store.ToggleSaved(title);save.Content=store.IsSaved(title)?"✓ My List":"+ My List";};actions.Children.Add(save);}
        else actions.Children.Add(Ui.AsyncButton("Explore title",()=>ShowDetails(title)));
    }
    private IEnumerable<Title> AccountContinue()=>account.Get("rows").Array().Where(r=>r.Text("name").StartsWith("Continue Watching",StringComparison.Ordinal)).SelectMany(r=>r.Get("titles").Array()).Select(x=>Movy.Core.Title.From(x)).OfType<Title>().Concat(accountNext.Where(p=>p.Key.StartsWith(AccountScope+":",StringComparison.Ordinal)).Select(p=>p.Value).OfType<Title>());
    private async Task ShowHome()
    {
        int token=Begin("Home");var refresh=Ui.AsyncButton("Refresh",async()=>{home=null;await ShowHome();});body.Children.Add(refresh);var pending=Ui.Text("Loading Movy…",16,Ui.Muted);body.Children.Add(pending);var target=body;
        try { var rows=await HomeRows();if(!Current(token))return;target.Children.Remove(pending);var activity=Ui.Column();target.Children.Add(activity);void Recent(){activity.Children.Clear();Rail(activity,"Continue Watching",store.Continue(AccountContinue(),AccountScope),true);}
            Recent();if(rows.SelectMany(r=>r.Titles).FirstOrDefault() is { } feature)Hero(target,feature);var providers=Ui.Row();foreach(var provider in Provider.All){var p=provider;var button=Ui.AsyncButton(p.Name,()=>ShowProvider(p));button.Background=(Brush)new BrushConverter().ConvertFromString(p.Color)!;providers.Children.Add(button);}target.Children.Add(providers);
            Rail(target,"My List · this PC",store.Saved);Rail(target,"Recently watched · this PC",store.Recent);foreach(var row in rows)Rail(target,row.Name,row.Titles);
            try { var snapshot=await connections.Account(ct:lifetime.Token);if(!Current(token))return;AdoptAccount(snapshot);Recent();
                foreach(var completed in snapshot.Get("completedSeries").Array().Select(x=>Movy.Core.Title.From(x)).OfType<Title>()){string key=AccountScope+":"+completed.Slug;if(!accountNextLoaded.TryGetValue(key,out var loaded)||(DateTime.UtcNow-loaded).TotalMinutes>=15){try{var next=await catalog.NextEpisode(completed,lifetime.Token);if(!Current(token))return;accountNext[key]=next;accountNextLoaded[key]=DateTime.UtcNow;}catch{continue;}Recent();}}
                var watchlists=Ui.Column();target.Children.Add(watchlists);foreach(var row in snapshot.Get("rows").Array().Where(r=>!r.Text("name").StartsWith("Continue Watching",StringComparison.Ordinal)))Rail(watchlists,row.Text("name"),row.Get("titles").Array().Select(x=>Movy.Core.Title.From(x)).OfType<Title>());
            } catch { /* Browsing works without an account or WebView2 installation. */ }
            foreach(var completed in store.Recent.Where(x=>x.IsSeries&&PlaybackStore.Completed(x.Resume,x.Duration)).ToArray()){try{store.QueueNext(completed,await catalog.NextEpisode(completed,lifetime.Token));}catch{}if(!Current(token))return;Recent();}
        }catch(OperationCanceledException){}catch{if(Current(token)){target.Children.Remove(pending);Error(target,ShowHome);}}
    }
    private async Task ShowRecommended(string type)
    {
        int token=Begin(type=="movie"?"Movies":"Series");var target=body;var tabs=Ui.Row();tabs.Children.Add(Ui.Button("✓ Recommended",()=>{}));tabs.Children.Add(Ui.AsyncButton("Library",()=>ShowLibrary(type)));target.Children.Add(tabs);target.Children.Add(Ui.Text("Movy's featured and popular collections · not personalized predictions",14,Ui.Muted));
        try{var rows=await HomeRows();if(!Current(token))return;var filtered=rows.Select(r=>new Row(r.Name,r.Titles.Where(t=>t.IsSeries==(type=="tv")).ToArray())).Where(r=>r.Titles.Count>0).ToArray();if(filtered.FirstOrDefault()?.Titles.FirstOrDefault() is { } first)Hero(target,first);foreach(var row in filtered)Rail(target,row.Name,row.Titles);}catch{if(Current(token))Error(target,()=>ShowRecommended(type));}
    }
    private async Task ShowProvider(Provider provider)
    {
        Begin(provider.Name);var target=body;target.Children.Add(Ui.Text("Movy metadata ranking · US movie availability / series network · not weekly charts",14,Ui.Muted));var tabs=Ui.Row();tabs.Children.Add(Ui.AsyncButton("Movie library / genres",()=>ShowLibrary("movie",provider)));tabs.Children.Add(Ui.AsyncButton("Series library / genres",()=>ShowLibrary("tv",provider)));target.Children.Add(tabs);
        var sort=new ComboBox { ItemsSource=new[]{"Popular","Recently released"},SelectedIndex=0 };target.Children.Add(sort);var sections=Ui.Column();target.Children.Add(sections);
        async Task Populate(){sections.Children.Clear();Sort order=sort.SelectedIndex==1?Sort.ReleaseNewest:Sort.Popular;await Task.WhenAll(GridSection(sections,"Movies","movie",provider,0,order),GridSection(sections,"Series","tv",provider,0,order));}
        sort.SelectionChanged+=async(_,_)=>await Populate();await Populate();
    }
    private async Task ShowLibrary(string type,Provider? provider=null)
    {
        int token=Begin((provider==null?"":provider.Name+" · ")+(type=="movie"?"Movie library":"Series library"));var target=body;var controls=Ui.Row();var sorts=new ComboBox { ItemsSource=new[]{"Release date ↓","Release date ↑","Title A–Z","Title Z–A","Popularity","Highest rated"},SelectedIndex=0 };var genres=new ComboBox { ItemsSource=new[]{new Genre(0,"All categories")},DisplayMemberPath="Name",SelectedIndex=0 };controls.Children.Add(sorts);controls.Children.Add(genres);target.Children.Add(controls);var section=Ui.Column();target.Children.Add(section);
        Task Populate(){section.Children.Clear();return GridSection(section,"Browse","movie"==type?"movie":"tv",provider,((Genre?)genres.SelectedItem)?.Id??0,(Sort)sorts.SelectedIndex);}
        bool configuring=true;sorts.SelectionChanged+=async(_,_)=>{if(!configuring)await Populate();};genres.SelectionChanged+=async(_,_)=>{if(!configuring)await Populate();};controls.Children.Add(Ui.AsyncButton("Refresh",()=>ShowLibrary(type,provider)));
        try{await Populate();if(!Current(token))return;var items=await catalog.Genres(type,lifetime.Token);if(!Current(token))return;genres.ItemsSource=new[]{new Genre(0,"All categories")}.Concat(items).ToArray();genres.SelectedIndex=0;}catch{}finally{configuring=false;}
    }
    private async Task GridSection(StackPanel target,string label,string type,Provider? provider,int genre,Sort sort)
    {
        target.Children.Add(Ui.Heading(label));var cards=Ui.Row();target.Children.Add(cards);var status=Ui.Text("",13,Ui.Muted);target.Children.Add(status);int page=0;bool fetching=false;var seen=new HashSet<string>();Button more=null!;
        async Task Load(){if(fetching)return;int token=generation;fetching=true;more.IsEnabled=false;status.Text="Loading…";
            try{var result=await catalog.Library(type,provider,genre,sort,page+1,lifetime.Token);if(!Current(token)||!target.Children.Contains(cards))return;int added=0;Button? first=null;foreach(var title in result.Titles.Where(t=>seen.Add(t.Slug))){var card=Card(title);first??=card;cards.Children.Add(card);added++;}page=result.Number;status.Text=$"{seen.Count} titles · {page} page(s)";more.Visibility=added==0||page>=Math.Min(result.TotalPages,500)?Visibility.Collapsed:Visibility.Visible;if(page>1&&first!=null){first.Focus();first.BringIntoView();}more.Content="Load more "+label.ToLowerInvariant();}
            catch(OperationCanceledException){}catch{status.Text="Could not load this page. Retry keeps your existing titles.";more.Content="Retry page "+(page+1);}finally{fetching=false;more.IsEnabled=true;}}
        more=Ui.AsyncButton("Load more "+label.ToLowerInvariant(),Load);target.Children.Add(more);await Load();
    }
    private Task ShowSaved(){Begin("My List · this PC");var cards=Ui.Row();body.Children.Add(cards);foreach(var title in store.Saved)cards.Children.Add(Card(title));if(store.Saved.Count==0)body.Children.Add(Ui.Text("Save a title from its details to see it here.",16,Ui.Muted));return Task.CompletedTask;}
    private Task ShowSearch()
    {
        Begin("Search");var input=new TextBox { FontSize=20,Margin=new Thickness(0,0,0,20) };body.Children.Add(input);var results=Ui.Row();body.Children.Add(results);var status=Ui.Text("Search movies and series · starts after a short typing pause",14,Ui.Muted);body.Children.Add(status);CancellationTokenSource? query=null;
        input.TextChanged+=async(_,_)=>{query?.Cancel();query?.Dispose();query=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);var ct=query.Token;int token=generation;string text=input.Text.Trim();results.Children.Clear();if(text.Length<2)return;
            try{await Task.Delay(650,ct);status.Text="Searching…";var titles=await catalog.Search(text,ct);if(!Current(token)||ct.IsCancellationRequested)return;foreach(var title in titles)results.Children.Add(Card(title));status.Text=titles.Count+" results";}catch(OperationCanceledException){}catch{if(Current(token)&&!ct.IsCancellationRequested)status.Text=Failure;}};input.Focus();return Task.CompletedTask;
    }
    private async Task ShowDetails(Title title)
    {
        int token=Begin(title.Name,true);var target=body;Hero(target,title,full:true);var pending=Ui.Text("Loading details…",14,Ui.Muted);target.Children.Add(pending);
        try{var details=await catalog.Details(title,lifetime.Token);if(!Current(token))return;target.Children.Clear();target.Children.Add(Ui.Button("‹ Back",Back));var detailed=title with { Description=details.Text("desc",title.Description),Backdrop=Security.Image(details.Text("background",title.Backdrop)),Rating=details.Number("score")>0?details.Number("score"):title.Rating,Year=details.Text("year",title.Year) };Hero(target,detailed,details,true);
            string genres=string.Join(" · ",details.Get("genres").Array().Select(x=>x.ValueKind==JsonValueKind.String?x.GetString():x.Text("name")));if(genres.Length>0)target.Children.Add(Ui.Text(genres,15,Ui.Muted));
            if(title.IsSeries){var selection=new ComboBox { DisplayMemberPath="Label" };var seasons=details.Get("seasons").Array().Select((s,i)=>new{Number=(int)(s.Number("season_number")>0?s.Number("season_number"):i+1),Label=s.Text("name","Season "+(i+1))}).Where(s=>s.Number>0).ToArray();selection.ItemsSource=seasons;target.Children.Add(selection);var episodes=Ui.Column();target.Children.Add(episodes);int request=0;
                selection.SelectionChanged+=async(_,_)=>{int next=++request;episodes.Children.Clear();if(selection.SelectedIndex<0)return;int season=seasons[selection.SelectedIndex].Number;try{var list=await catalog.Episodes(title.Id,season,lifetime.Token);if(!Current(token)||next!=request)return;foreach(var item in list){int number=(int)item.Number("episode_number");if(number<=0)continue;string date=item.Text("air_date");bool aired=DateOnly.TryParseExact(date,"yyyy-MM-dd",out var release)&&release<=DateOnly.FromDateTime(DateTime.Today);var ep=detailed with { Slug=$"/tv/{title.Id}/{season}/{number}" };var button=Ui.AsyncButton($"E{number} · {item.Text("name","Episode "+number)}"+(aired?"":" · Not yet released"),()=>Play(ep));button.IsEnabled=aired;button.HorizontalContentAlignment=HorizontalAlignment.Left;episodes.Children.Add(button);}}catch{if(Current(token)&&next==request)episodes.Children.Add(Ui.Text("Episodes could not load. Select the season again to retry.",14,Ui.Muted));}};
                if(seasons.Length>0)selection.SelectedIndex=0;else target.Children.Add(Ui.Text("Season metadata is unavailable. Play defaults to S1 E1.",14,Ui.Muted));}
            var cast=details.Get("cast").Array().Select(x=>x.Text("name")).Where(x=>x.Length>0).Take(12).ToArray();if(cast.Length>0){target.Children.Add(Ui.Heading("Cast",20));target.Children.Add(Ui.Text(string.Join(" · ",cast),15,Ui.Muted));}
            foreach(string key in new[]{"recommendations","similar"}){var values=details.Get(key);if(values.ValueKind==JsonValueKind.Object)values=values.Get("results");Rail(target,Catalog.Humanize(key),values.Array().Select(x=>Movy.Core.Title.From(x)??Movy.Core.Title.From(x,title.IsSeries?"tv":"movie")).OfType<Title>());}
        }catch{if(Current(token)){target.Children.Remove(pending);target.Children.Add(Ui.Text("Extra details could not load. You can still try Play.",14,Ui.Muted));}}
    }
    private void AdoptAccount(JsonElement snapshot){string before=AccountScope;account=snapshot;if(before!=AccountScope){history.Clear();accountNext.Clear();accountNextLoaded.Clear();}}
    private async Task ShowAccount()
    {
        int token=Begin("Account");var target=body;target.Children.Add(Ui.Text("Connecting to Movy…",16,Ui.Muted));
        try{var snapshot=await connections.Account(ct:lifetime.Token);if(!Current(token))return;AdoptAccount(snapshot);target.Children.Clear();target.Children.Add(Ui.Heading("Movy account"));
            if(snapshot.Get("signedIn").ValueKind!=JsonValueKind.True){target.Children.Add(Ui.Text("Sign in to read your Movy profile, watchlists and Continue Watching. Desktop progress is saved on this PC only.",16,Ui.Muted));var username=new TextBox { Margin=new Thickness(0,12,0,12) };var password=new PasswordBox();target.Children.Add(Ui.Text("Username"));target.Children.Add(username);target.Children.Add(Ui.Text("Password"));target.Children.Add(password);var status=Ui.Text("",14,Ui.Muted);target.Children.Add(status);
                target.Children.Add(Ui.AsyncButton("Sign in",async()=>{try{var result=await connections.Account("login",new{username=username.Text.Trim(),password=password.Password},lifetime.Token);password.Clear();AdoptAccount(result);await ShowAccount();}catch{password.Clear();status.Text="Sign-in failed. Check your credentials and try again.";}}));return;}
            target.Children.Add(Ui.Text(snapshot.Text("username")+" · "+snapshot.Text("email"),18));target.Children.Add(Ui.Text("Selected profile: "+snapshot.Get("profile").Text("name","None"),15,Ui.Gold));
            foreach(var profile in snapshot.Get("profiles").Array()){var p=profile.Clone();target.Children.Add(Ui.AsyncButton("Use profile · "+p.Text("name"),async()=>{bool pinUnknown=p.Get("hasPin").ValueKind==JsonValueKind.Undefined&&p.Get("pinEnabled").ValueKind==JsonValueKind.Undefined&&p.Get("pinRequired").ValueKind==JsonValueKind.Undefined;bool pin=pinUnknown||p.Get("hasPin").ValueKind==JsonValueKind.True||p.Get("pinEnabled").ValueKind==JsonValueKind.True||p.Get("pinRequired").ValueKind==JsonValueKind.True;string? code=pin?Ui.Prompt(this,"Profile PIN",p.Text("name"),true):"";if(code==null)return;try{AdoptAccount(await connections.Account("profile",new{id=p.Text("id"),pin=code},lifetime.Token));await ShowAccount();}catch{MessageBox.Show("Profile verification failed. Check the PIN and try again.","Movy account");}}));}
            if(snapshot.Get("partial").ValueKind==JsonValueKind.True)target.Children.Add(Ui.Text("Some account lists could not load. Refresh to retry.",14,Ui.Muted));foreach(var row in snapshot.Get("rows").Array())Rail(target,row.Text("name"),row.Get("titles").Array().Select(x=>Movy.Core.Title.From(x)).OfType<Title>());
            target.Children.Add(Ui.AsyncButton("Refresh account",ShowAccount));target.Children.Add(Ui.AsyncButton("Sign out",async()=>{AdoptAccount(await connections.Account("logout",ct:lifetime.Token));await ShowAccount();}));
        }catch{if(Current(token)){target.Children.Clear();target.Children.Add(Ui.Text("Account access requires Microsoft Edge WebView2 Runtime and an internet connection. Android sign-in does not transfer automatically to Windows.",16,Ui.Muted));target.Children.Add(Ui.AsyncButton("Retry",ShowAccount));target.Children.Add(Ui.Button("WebView2 Runtime information",()=>System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("https://developer.microsoft.com/en-us/microsoft-edge/webview2/"){UseShellExecute=true})));}}
    }
    private async Task Play(Title title,bool startOver=false)
    {
        if(!Security.TitlePath(title.Slug))return;if(title.IsSeries&&title.Slug.Split('/').Length!=5)title=title with {Slug=title.Root+"/1/1"};
        var overlay=new Grid { Background=new SolidColorBrush(Color.FromRgb(17,20,22)) };Grid.SetColumnSpan(overlay,2);var box=Ui.Column();box.MaxWidth=620;box.VerticalAlignment=VerticalAlignment.Center;box.HorizontalAlignment=HorizontalAlignment.Center;box.Children.Add(Ui.Text("MOVY",18,Ui.Gold));box.Children.Add(Ui.Heading(title.Name,32));var status=Ui.Text("Preparing playback…",18,Ui.Muted);box.Children.Add(status);using var cancel=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);box.Children.Add(Ui.Button("Cancel",()=>cancel.Cancel()));overlay.Children.Add(box);root.Children.Add(overlay);
        try{if(!MpvRuntime.Installed){if(MessageBox.Show("Native playback needs the official mpv player (~39 MB). Download and verify it now? It will be cached on this PC.","Movy Desktop",MessageBoxButton.YesNo)!=MessageBoxResult.Yes)return;await MpvRuntime.Install(text=>status.Text=text,cancel.Token);}
            while(true){
                var stream=await connections.Resolve(title.Slug,"",text=>status.Text=text,cancel.Token);store.Started(title,AccountScope);double position=startOver?0:title.Resume>0?title.Resume:store.Position(title.Slug);
                var player=new PlayerWindow(title,stream,position,store,catalog,connections){Owner=this};IsEnabled=false;
                try{player.Show();await player.Completion;}finally{IsEnabled=true;Activate();}
                if(player.NextTitle==null)break;title=player.NextTitle;startOver=true;status.Text="Preparing "+title.EpisodeLabel+"…";
            }
        }catch(OperationCanceledException){}catch{MessageBox.Show("This title could not start. Check your connection and WebView2 Runtime, then retry. No web-player fallback was opened.","Movy playback");}
        finally{root.Children.Remove(overlay);}
    }
    internal BrowserConnections Connections=>connections;
    internal void ShowFixture()
    {
        Begin("Home");body.Children.Add(Ui.Text("Native Windows layout preview · fixture data / no live stream",14,Ui.Muted));
        var movie=new Title(603,"/movie/603","Across the Ocean",Year:"2026",Rating:8.2,Resume:1200,Duration:7200);var series=new Title(1399,"/tv/1399/2/3","The Long Way Home",Year:"2026",Rating:8.5,Resume:900,Duration:3000);var next=series with{Slug="/tv/1399/2/4",Name="Northern Lights",NextEpisode=true,Resume=0};
        Rail(body,"Continue Watching",new[]{movie,series,next},true);Hero(body,movie with{Description="Discover your next favorite in Movy's featured and popular collections. Native libraries, real playback controls, and your account activity in one place."});Rail(body,"Recommended on Movy",new[]{movie,series});
    }
}
