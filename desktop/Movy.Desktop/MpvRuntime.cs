using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace Movy.Desktop;
public static class MpvRuntime
{
    public const string Version = "0.41.0";
    public const string Url = "https://github.com/mpv-player/mpv/releases/download/v0.41.0/mpv-v0.41.0-x86_64-w64-mingw32.zip";
    public const string Sha256 = "a49811c0752c108b8260636f9c6f6fcb97406641c98b30f1e7b500dfb20177de";
    public static string Executable => Path.Combine(App.DataDirectory, "Player", Version, "mpv.exe");
    public static bool Installed {
        get {
            try { var hashes = JsonSerializer.Deserialize<Dictionary<string,string>>(File.ReadAllText(Executable + ".sha256"));
                return hashes != null && hashes.ContainsKey("mpv.exe") && hashes.All(pair => {
                    if(Path.GetFileName(pair.Key)!=pair.Key)return false;
                    string file=Path.Combine(Path.GetDirectoryName(Executable)!,pair.Key);
                    return File.Exists(file)&&pair.Value==Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(file)));
                });
            } catch (IOException) { return false; } catch (JsonException) { return false; }
        }
    }
    public static async Task Install(Action<string> progress, CancellationToken ct)
    {
        string directory = Path.GetDirectoryName(Executable)!; Directory.CreateDirectory(directory); string download = Path.Combine(directory, "download-" + Guid.NewGuid().ToString("N") + ".zip");
        try {
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) }; using var response = await http.GetAsync(Url, HttpCompletionOption.ResponseHeadersRead, ct); response.EnsureSuccessStatusCode();
            await using (var output = File.Create(download)) { await using var input = await response.Content.ReadAsStreamAsync(ct); byte[] buffer = new byte[65536]; long total = 0; int read;
                while ((read = await input.ReadAsync(buffer, ct)) > 0) { total += read; if (total > 100 * 1024 * 1024) throw new InvalidDataException("Player download was too large."); await output.WriteAsync(buffer.AsMemory(0, read), ct); progress($"Downloading verified player · {total / 1048576.0:0.0} MB"); }
            }
            await using (var check = File.OpenRead(download)) if (Convert.ToHexString(await SHA256.HashDataAsync(check, ct)).ToLowerInvariant() != Sha256) throw new InvalidDataException("Player verification failed. Nothing was installed.");
            using var outer = ZipFile.OpenRead(download); var nested = outer.Entries.SingleOrDefault(e => e.FullName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) ?? throw new InvalidDataException("Player archive format changed.");
            if (nested.Length > 100 * 1024 * 1024) throw new InvalidDataException("Player archive was too large.");
            using var bytes = new MemoryStream(); await using (var input = nested.Open()) await input.CopyToAsync(bytes, ct); bytes.Position = 0; using var archive = new ZipArchive(bytes);
            var entries = archive.Entries.Where(e => Path.GetFileName(e.FullName) == e.FullName && (e.FullName == "mpv.exe" || e.FullName.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))).ToArray();
            if (!entries.Any(e => e.FullName == "mpv.exe") || entries.Sum(e => e.Length) > 300 * 1024 * 1024) throw new InvalidDataException("Player archive format changed.");
            // Only extract root-level player/DLL files; never honor archive paths.
            var hashes = new Dictionary<string,string>();
            foreach (var entry in entries) {
                string destination = Path.Combine(directory, entry.FullName), staging = destination + ".tmp";
                try { await using (var output = File.Create(staging)) await using (var input = entry.Open()) await input.CopyToAsync(output, ct); ct.ThrowIfCancellationRequested(); File.Move(staging, destination, true); hashes[entry.FullName] = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(destination, ct))); }
                finally { if (File.Exists(staging)) File.Delete(staging); }
            }
            await File.WriteAllTextAsync(Executable + ".sha256", JsonSerializer.Serialize(hashes), ct);
        } finally { if (File.Exists(download)) File.Delete(download); if (File.Exists(Executable + ".tmp")) File.Delete(Executable + ".tmp"); }
    }
}
