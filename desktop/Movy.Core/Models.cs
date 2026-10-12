using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Movy.Core;
public static class Json
{
    public static string Text(this JsonElement e, string key, string fallback = "") => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(key, out var v) && v.ValueKind != JsonValueKind.Null ? v.ToString() : fallback;
    public static double Number(this JsonElement e, string key) => double.TryParse(e.Text(key), NumberStyles.Float, CultureInfo.InvariantCulture, out var n) && double.IsFinite(n) ? n : 0;
    public static JsonElement Get(this JsonElement e, string key) => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(key, out var v) ? v : default;
    public static IEnumerable<JsonElement> Array(this JsonElement e) => e.ValueKind == JsonValueKind.Array ? e.EnumerateArray() : [];
}
public static class Security
{
    public static bool TitlePath(string path) => Regex.IsMatch(path, @"^/(movie|tv)/\d+(?:/\d+/\d+)?$");
    public static bool Https(string address) => Uri.TryCreate(address, UriKind.Absolute, out var uri) && uri.Scheme == "https" && string.IsNullOrEmpty(uri.UserInfo);
    public static bool Site(string address) => Https(address) && new Uri(address).Host is "movy.sx" or "www.movy.sx";
    public static string RootKey(string path) => TitlePath(path) ? string.Join('/', path.Split('/').Take(3)) : "";
    public static string Image(string address) => Https(address) ? address.Replace("output=webp", "output=jpg", StringComparison.OrdinalIgnoreCase) : "";
}
public record Title(long Id, string Slug, string Name, string Poster = "", string Backdrop = "", string Description = "", string Year = "", double Rating = 0,
    double Resume = 0, double Duration = 0, long Updated = 0, bool NextEpisode = false, string AvailableOn = "")
{
    public bool IsSeries => Slug.StartsWith("/tv/", StringComparison.Ordinal);
    public string Root => Security.RootKey(Slug);
    public string Metadata => string.Join("  ·  ", new[] { Year, Rating > 0 ? $"★ {Rating:0.0} / 10" : "", IsSeries ? "Series" : "Movie" }.Where(s => s.Length > 0));
    public string EpisodeLabel => IsSeries && Slug.Split('/').Length == 5 ? $"S{Slug.Split('/')[3]} E{Slug.Split('/')[4]}" : "";
    public static Title? From(JsonElement e, string? mediaType = null)
    {
        string type = mediaType ?? e.Text("mediaType", e.Text("media_type", "movie"));
        long id = (long)e.Number("id"); string slug = e.Text("slug", $"/{type}/{id}");
        if (!Security.TitlePath(slug) || id <= 0) return null;
        string ImagePath(string path) => string.IsNullOrEmpty(path) ? "" : "https://wsrv.nl/?url=" + Uri.EscapeDataString("https://image.tmdb.org/t/p/w500" + path) + "&output=jpg&q=80";
        string date = e.Text("release_date", e.Text("first_air_date"));
        return new Title(id, slug, e.Text("title", e.Text("name", "Untitled")), Security.Image(e.Text("poster", ImagePath(e.Text("poster_path")))),
            Security.Image(e.Text("image", e.Text("background", ImagePath(e.Text("backdrop_path"))))), e.Text("description", e.Text("overview")), date.Length >= 4 ? date[..4] : "", e.Number(mediaType == null ? "rating" : "vote_average"),
            e.Number("resumeMs") / 1000, e.Number("durationMs") / 1000, (long)e.Number("updated"), e.Get("nextEpisode").ValueKind == JsonValueKind.True, e.Text("availableOn"));
    }
}
public record Row(string Name, IReadOnlyList<Title> Titles);
public record Provider(string Name, int Network, int WatchId, string Color)
{
    public static readonly Provider[] All = MovyConfig.Providers;
}
public enum Sort { ReleaseNewest, ReleaseOldest, Alphabetical, AlphabeticalReverse, Popular, Rating }
public record Genre(int Id, string Name);
public record Page(IReadOnlyList<Title> Titles, int Number, int TotalPages);
public record Caption(string Url, string Label, string Language, string Format = "vtt");
public record Source(string Url, string Label);
public record Server(string Name, string Label);
public record ResolvedStream(string Url, IReadOnlyDictionary<string,string> Headers, IReadOnlyList<Source> Sources, IReadOnlyList<Caption> Captions, IReadOnlyList<Server> Servers, string Server)
{
    public static ResolvedStream From(string url, IReadOnlyDictionary<string,string> headers, JsonElement data)
    {
        if (!Security.Https(url)) throw new InvalidDataException("A secure stream was not returned.");
        return new(url, headers,
            data.Get("sources").Array().Where(x => Security.Https(x.Text("url"))).Select(x => new Source(x.Text("url"), x.Text("label", "Auto"))).DistinctBy(x => x.Url).Take(100).ToArray(),
            data.Get("subtitles").Array().Where(x => Security.Https(x.Text("url"))).Select(x => new Caption(x.Text("url"), x.Text("label", "Subtitles"), x.Text("lang"), x.Text("format", "vtt"))).DistinctBy(x => x.Url).Take(60).ToArray(),
            data.Get("servers").Array().Where(x => x.Text("name").Length is > 0 and <= 60).Select(x => new Server(x.Text("name"), x.Text("label", x.Text("name")))).DistinctBy(x => x.Name).Take(40).ToArray(), data.Text("server"));
    }
}
