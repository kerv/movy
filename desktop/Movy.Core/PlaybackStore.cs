using System.Text.Json;

namespace Movy.Core;
public sealed class PlaybackStore
{
    public sealed class State
    {
        public List<Title> Recent { get; set; } = [];
        public List<Title> Saved { get; set; } = [];
        public List<Title> Next { get; set; } = [];
        public HashSet<string> Hidden { get; set; } = [];
    }
    private readonly string file;
    private State data = new();
    public PlaybackStore(string file)
    {
        this.file = file;
        try { if (File.Exists(file)) data = JsonSerializer.Deserialize<State>(File.ReadAllText(file)) ?? new(); }
        catch (JsonException) { /* Preserve corrupt file until the next explicit save. */ }
        catch (IOException) { }
    }
    public IReadOnlyList<Title> Saved => data.Saved;
    public IReadOnlyList<Title> Recent => data.Recent;
    public static bool Completed(double position, double duration) => double.IsFinite(position) && double.IsFinite(duration) && duration > 0 && position >= Math.Max(duration * .95, duration - 60);
    public double Position(string slug) => data.Recent.FirstOrDefault(x => x.Slug == slug) is { } title && !Completed(title.Resume, title.Duration) ? title.Resume : 0;
    public bool IsSaved(Title title) => data.Saved.Any(x => x.Root == title.Root);
    public void ToggleSaved(Title title) { if (IsSaved(title)) data.Saved.RemoveAll(x => x.Root == title.Root); else data.Saved.Add(title with { Slug = title.Root, Resume = 0 }); Save(); }
    public bool Hidden(Title title, string scope) => data.Hidden.Contains(scope + ":" + title.Root);
    public void Dismiss(Title title, string scope) { data.Hidden.Add(scope + ":" + title.Root); Save(); }
    public void Started(Title title, string scope) { data.Hidden.Remove("device:" + title.Root); data.Hidden.Remove(scope + ":" + title.Root); Save(); }
    public bool Progress(Title title, double position, double duration)
    {
        if (!double.IsFinite(position) || !double.IsFinite(duration) || position < 15 || duration <= 0) return false;
        bool completed = Completed(position, duration), wasComplete = data.Recent.Any(x => x.Slug == title.Slug && Completed(x.Resume, x.Duration));
        data.Recent.RemoveAll(x => x.Slug == title.Slug || title.IsSeries && x.Root == title.Root);
        data.Recent.Insert(0, title with { Resume = position, Duration = duration, NextEpisode=false, AvailableOn="", Updated = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });
        data.Recent = data.Recent.Take(40).ToList(); if (!completed || !wasComplete) data.Next.RemoveAll(x => x.Root == title.Root); Save(); return completed && !wasComplete;
    }
    public void QueueNext(Title completed, Title? next)
    {
        // An older async lookup must never overwrite a newer episode's progress.
        var latest = data.Recent.FirstOrDefault(x => x.Root == completed.Root);
        if (next == null || latest?.Slug != completed.Slug || !Completed(latest.Resume, latest.Duration)) return;
        data.Next.RemoveAll(x => x.Root == completed.Root); data.Next.Insert(0, next); data.Next = data.Next.Take(40).ToList(); Save();
    }
    public static bool Aired(Title title) => !title.NextEpisode || DateOnly.TryParseExact(title.AvailableOn, "yyyy-MM-dd", out var date) && date <= DateOnly.FromDateTime(DateTime.Today);
    public IReadOnlyList<Title> Continue(IEnumerable<Title>? account = null, string scope = "device") =>
        data.Recent.Where(x => !Completed(x.Resume, x.Duration)).Concat(data.Next.Where(Aired)).OrderByDescending(x => x.Updated)
            .Where(x => !Hidden(x, "device") && !Hidden(x, scope)).Concat((account ?? []).Where(x => Aired(x) && !Hidden(x, scope)))
            .DistinctBy(x => x.Root).Take(40).ToArray();
    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(file))!);
        string temporary = file + ".tmp"; File.WriteAllText(temporary, JsonSerializer.Serialize(data)); File.Move(temporary, file, true);
    }
}
