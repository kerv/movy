using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using Movy.Core;

namespace Movy.Desktop;
public sealed class MpvClient : IDisposable
{
    private readonly ConcurrentDictionary<long,TaskCompletionSource<JsonElement>> pending = new();
    private readonly SemaphoreSlim writeGate = new(1);
    private readonly CancellationTokenSource lifetime = new();
    private Process? process;
    private NamedPipeClientStream? pipe;
    private StreamWriter? writer;
    private long sequence;
    private TaskCompletionSource? fileLoaded;
    public event Action<JsonElement>? EventReceived;
    public async Task Start(nint hwnd)
    {
        string name = "movy-" + Guid.NewGuid().ToString("N");
        var info = new ProcessStartInfo(MpvRuntime.Executable) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetDirectoryName(MpvRuntime.Executable)!, RedirectStandardError = true, RedirectStandardOutput = true };
        foreach (string flag in new[] { "--no-config", "--load-scripts=no", "--ytdl=no", "--osc=no", "--idle=yes", "--keep-open=yes", "--terminal=no", "--msg-level=all=no", "--input-default-bindings=no", "--input-vo-keyboard=no", "--hwdec=auto-safe", "--input-ipc-server=" + name }) info.ArgumentList.Add(flag);
        if (hwnd != 0) { info.ArgumentList.Add("--wid=" + unchecked((uint)hwnd.ToInt64())); info.ArgumentList.Add("--force-window=yes"); }
        else { info.ArgumentList.Add("--vo=null"); info.ArgumentList.Add("--ao=null"); }
        process = Process.Start(info) ?? throw new InvalidOperationException("The native player could not start.");
        process.BeginErrorReadLine(); process.BeginOutputReadLine();
        pipe = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous); await pipe.ConnectAsync(10000, lifetime.Token);
        writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen:true) { AutoFlush = true };
        _ = ReadLoop(); await Set("pause", true);
    }
    private async Task ReadLoop()
    {
        try {
            using var reader = new StreamReader(pipe!, Encoding.UTF8, leaveOpen:true);
            while (!lifetime.IsCancellationRequested && await reader.ReadLineAsync(lifetime.Token) is { } line) {
                using var doc = JsonDocument.Parse(line); var message = doc.RootElement;
                if (message.Get("request_id").ValueKind == JsonValueKind.Number && pending.TryRemove((long)message.Number("request_id"), out var completion)) {
                    if (message.Text("error") == "success") completion.TrySetResult(message.Get("data").CloneSafe());
                    else completion.TrySetException(new InvalidOperationException("The player could not apply that option."));
                } else if (message.Text("event") == "file-loaded") fileLoaded?.TrySetResult();
                else if (message.Text("event") == "end-file" && message.Text("reason") == "error") fileLoaded?.TrySetException(new InvalidOperationException("This stream could not play. Try another source."));
                if (message.Get("event").ValueKind == JsonValueKind.String) EventReceived?.Invoke(message.Clone());
            }
        } catch (Exception e) when (e is IOException or OperationCanceledException or ObjectDisposedException or JsonException) { }
        finally { foreach (var request in pending.Values) request.TrySetException(new IOException("The native player disconnected.")); pending.Clear(); fileLoaded?.TrySetException(new IOException("The native player disconnected.")); }
    }
    private async Task<JsonElement> Send(long id, string command)
    {
        var result = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously); pending[id] = result;
        try { await writeGate.WaitAsync(lifetime.Token); try { await writer!.WriteLineAsync(command.AsMemory(), lifetime.Token); } finally { writeGate.Release(); } return await result.Task.WaitAsync(TimeSpan.FromSeconds(10), lifetime.Token); }
        finally { pending.TryRemove(id, out _); }
    }
    public Task<JsonElement> Command(params object[] command) { long id = Interlocked.Increment(ref sequence); return Send(id, MpvProtocol.Command(id, command)); }
    public async Task Set(string property, object value) => await Command("set_property", property, value);
    public Task<JsonElement> Get(string property) => Command("get_property", property);
    public async Task Load(ResolvedStream stream, double position, bool paused)
    {
        var headers = MpvProtocol.HeadersFor(stream, stream.Url); headers.TryGetValue("User-Agent", out string? agent); headers.TryGetValue("Referer", out string? referer);
        await Set("user-agent", agent ?? "MovyDesktop/1.20"); await Set("referrer", referer ?? Catalog.Site + "/");
        await Set("http-header-fields", headers.Where(h => h.Key is not ("User-Agent" or "Referer")).Select(h => h.Key + ": " + h.Value).ToArray());
        fileLoaded = new(TaskCreationOptions.RunContinuationsAsynchronously); long id = Interlocked.Increment(ref sequence);
        await Send(id, MpvProtocol.Load(id, stream.Url, position, paused)); await fileLoaded.Task.WaitAsync(TimeSpan.FromSeconds(30), lifetime.Token);
    }
    public void Dispose()
    {
        lifetime.Cancel(); pipe?.Dispose();
        if (process != null) { try { if (!process.HasExited) process.Kill(); } catch (InvalidOperationException) { } process.Dispose(); }
    }
}
internal static class JsonCloneExtension { public static JsonElement CloneSafe(this JsonElement value) => value.ValueKind == JsonValueKind.Undefined ? default : value.Clone(); }
