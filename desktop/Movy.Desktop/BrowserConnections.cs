using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using Movy.Core;

namespace Movy.Desktop;
/// <summary>Hidden, origin-restricted account and on-demand resolver connections; no visible web UI.</summary>
public sealed class BrowserConnections : IDisposable
{
    private static string AccountUrl => Catalog.Site + "/__movy_desktop_account";
    private readonly Grid host;
    private readonly WebView2CompositionControl accountBrowser = new() { Width = 1, Height = 1, Focusable = false, IsHitTestVisible = false };
    private readonly SemaphoreSlim accountGate = new(1);
    private readonly Dictionary<int, TaskCompletionSource<JsonElement>> accountRequests = [];
    private Task? accountInitialization;
    private Task<CoreWebView2Environment>? environment;
    private int requestId;
    private bool disposed;
    public BrowserConnections(Grid host) { this.host = host; host.Children.Insert(0, new Border { Child=accountBrowser,Opacity=0,Width=1,Height=1,IsHitTestVisible=false }); }
    private Task<CoreWebView2Environment> EnvironmentTask() => environment ??= CoreWebView2Environment.CreateAsync(null, Path.Combine(App.DataDirectory, "Browser"), new CoreWebView2EnvironmentOptions("--autoplay-policy=no-user-gesture-required"));
    private static void Restrict(CoreWebView2 core)
    {
        core.Settings.AreDefaultContextMenusEnabled = false; core.Settings.AreDevToolsEnabled = false; core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreHostObjectsAllowed = false; core.Settings.IsBuiltInErrorPageEnabled = false; core.IsMuted = true;
        core.NewWindowRequested += (_, e) => e.Handled = true;
        core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
        core.DownloadStarting += (_, e) => e.Cancel = true;
    }
    private async Task InitializeAccount()
    {
        await accountBrowser.EnsureCoreWebView2Async(await EnvironmentTask()); var core = accountBrowser.CoreWebView2; Restrict(core);
        core.NavigationStarting += (_, e) => e.Cancel = e.Uri != AccountUrl;
        core.AddWebResourceRequestedFilter(AccountUrl, CoreWebView2WebResourceContext.Document);
        core.WebResourceRequested += (_, e) => {
            if (e.Request.Uri == AccountUrl) e.Response = core.Environment.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes("<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>")), 200, "OK", "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store");
        };
        core.WebMessageReceived += (_, e) => {
            if (disposed || e.Source != AccountUrl) return;
            try { using var doc = JsonDocument.Parse(e.TryGetWebMessageAsString()); var data = doc.RootElement;
                if (!accountRequests.Remove((int)data.Number("id"), out var completion)) return;
                if (data.Get("ok").ValueKind == JsonValueKind.True) completion.TrySetResult(data.Get("snapshot").Clone());
                else completion.TrySetException(new InvalidOperationException(data.Text("error", "Account connection failed.")));
            } catch (JsonException) { } catch (ArgumentException) { }
        };
        string script = "if(window.top===window && location.href===" + JsonSerializer.Serialize(AccountUrl) + "){window.__movyAccountBase="+JsonSerializer.Serialize(MovyConfig.Account)+";window.__movyNativeDeviceName='Movy Windows Desktop';window.__movyNativeDevicePrefix='movy-desktop-';window.movyAccountBridge={postMessage:function(v){chrome.webview.postMessage(v);}};\n" + await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "Assets", "native_account.js")) + "\n}";
        await core.AddScriptToExecuteOnDocumentCreatedAsync(script);
        var loaded = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        void Finished(object? _, CoreWebView2NavigationCompletedEventArgs e) { if(core.Source!=AccountUrl)return;if (e.IsSuccess) loaded.TrySetResult(); else loaded.TrySetException(new InvalidOperationException("Account connection could not initialize.")); }
        core.NavigationCompleted += Finished; core.Navigate(AccountUrl);
        try { await loaded.Task.WaitAsync(TimeSpan.FromSeconds(20)); } finally { core.NavigationCompleted -= Finished; }
    }
    public async Task<JsonElement> Account(string action = "read", object? args = null, CancellationToken ct = default)
    {
        if (action is not ("read" or "login" or "logout" or "profile")) throw new ArgumentException("Unsupported account action.");
        await accountGate.WaitAsync(ct); int id = 0;
        try {
            if (disposed) throw new ObjectDisposedException(nameof(BrowserConnections));
            try { await (accountInitialization ??= InitializeAccount()).WaitAsync(ct); } catch { accountInitialization = null; environment=null; throw; }
            id = ++requestId; var result = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously); accountRequests[id] = result;
            string request = JsonSerializer.Serialize(new { id, action, args = args ?? new { } });
            await accountBrowser.CoreWebView2.ExecuteScriptAsync("window.__movyAccountRequest(" + request + ");void 0;");
            return await result.Task.WaitAsync(TimeSpan.FromSeconds(75), ct);
        } finally { accountRequests.Remove(id); accountGate.Release(); }
    }
    public async Task<ResolvedStream> Resolve(string slug, string server, Action<string> progress, CancellationToken ct)
    {
        if (!Security.TitlePath(slug)) throw new ArgumentException("Invalid playback title.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromSeconds(45)); var token = deadline.Token;
        var browser = new WebView2CompositionControl { Width = 1280, Height = 720, IsHitTestVisible = false, Focusable = false };
        var hidden=new Border { Child=browser,Width=1280,Height=720,Opacity=0,IsHitTestVisible=false };host.Children.Insert(0,hidden);
        var pending = new Dictionary<string, Dictionary<string, string>>(); DateTime lastCapture = DateTime.UtcNow; string page = Catalog.Site + slug + "?play=true";
        try {
            await browser.EnsureCoreWebView2Async(await EnvironmentTask()); token.ThrowIfCancellationRequested(); var core = browser.CoreWebView2; Restrict(core);
            core.NavigationStarting += (_, e) => e.Cancel = !Security.Site(e.Uri);
            core.WebResourceResponseReceived += (_, e) => {
                if (token.IsCancellationRequested || e.Response.StatusCode is < 200 or >= 300 || !Security.Https(e.Request.Uri) || !new Uri(e.Request.Uri).AbsolutePath.EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase)) return;
                var headers = new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
                foreach (string name in new[] { "Referer", "Origin", "User-Agent", "Cookie" }) if (e.Request.Headers.Contains(name)) headers[name] = e.Request.Headers.GetHeader(name);
                pending[e.Request.Uri] = headers; while (pending.Count > 12) pending.Remove(pending.Keys.First()); lastCapture = DateTime.UtcNow;
            };
            string script = "if(window.top===window && (location.origin==='https://www.movy.sx'||location.origin==='https://movy.sx')){window.__movyNativeRequestedServer=" + JsonSerializer.Serialize(server) + ";\n" + await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "Assets", "native_resolver.js"), token) + "\n}";
            await core.AddScriptToExecuteOnDocumentCreatedAsync(script); core.Navigate(page); progress("Finding a stream on Movy…");
            while (true) {
                await Task.Delay(250, token); if (pending.Count == 0) continue;
                string raw = await core.ExecuteScriptAsync("JSON.stringify({chosen:window.__movyNativeServerChosen||'',acceptDefault:!!window.__movyNativeDefaultRequested,bundles:window.__movyNativeBundles||[],tracks:window.__movyNativeTracks||{},info:window.__movyNativeServerInfo?window.__movyNativeServerInfo():{}})");
                string? decoded = JsonSerializer.Deserialize<string>(raw); if (decoded == null) continue;
                using var doc = JsonDocument.Parse(decoded); var data = doc.RootElement;
                var bundles = data.Get("bundles").Array().ToArray(); var advertised = bundles.SelectMany(b => b.Get("sources").Array()).Select(s => s.Text("url")).ToHashSet();
                bool requestedReady = server.Length == 0 || data.Text("chosen") == server;
                var candidates = pending.Keys.Where(url => requestedReady && (server.Length == 0 || data.Get("acceptDefault").ValueKind == JsonValueKind.True || advertised.Contains(url))).ToArray();
                if (candidates.Length == 0) continue; progress("Preparing native playback…"); if ((DateTime.UtcNow - lastCapture).TotalSeconds < 2.5) continue;
                int Height(string url) { var m = Regex.Match(url, @"(?:s|[-_/])(\d{3,4})p", RegexOptions.IgnoreCase); return m.Success ? int.Parse(m.Groups[1].Value) : 0; }
                string selected = candidates.OrderByDescending(Height).First(); var chosen = bundles.FirstOrDefault(b => b.Get("sources").Array().Any(s => s.Text("url") == selected));
                if (chosen.ValueKind != JsonValueKind.Object) chosen = data.Get("tracks");
                var info = data.Get("info"); using var merged = JsonDocument.Parse(JsonSerializer.Serialize(new { sources = chosen.Get("sources").Array().ToArray(), subtitles = chosen.Get("subtitles").Array().ToArray(), servers = info.Get("servers").Array().ToArray(), server = info.Text("server") }));
                var headers = pending[selected]; headers.TryAdd("Referer", page); headers.TryAdd("Origin", Catalog.Site); headers["User-Agent"] = core.Settings.UserAgent;
                var cookies = await core.CookieManager.GetCookiesAsync(selected); if (cookies.Count > 0) headers["Cookie"] = string.Join("; ", cookies.Select(c => c.Name + "=" + c.Value));
                token.ThrowIfCancellationRequested(); return ResolvedStream.From(selected, headers, merged.RootElement);
            }
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new TimeoutException("Movy did not return a stream within 45 seconds. Please try another source."); }
        finally { if (browser.CoreWebView2 != null) browser.CoreWebView2.Stop(); host.Children.Remove(hidden); browser.Dispose(); }
    }
    public void Dispose() { disposed = true; foreach (var pending in accountRequests.Values) pending.TrySetCanceled(); accountRequests.Clear(); accountBrowser.Dispose(); }
}
