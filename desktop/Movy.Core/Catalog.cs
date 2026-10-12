using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Movy.Core;
public sealed class Catalog
{
    public static string Site => MovyConfig.Site;
    public static string Metadata => MovyConfig.Metadata;
    private readonly HttpClient http;
    public Catalog(HttpClient? client = null)
    {
        http = client ?? new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 MovyDesktop/1.20");
    }
    public static string LibraryUrl(string type, Provider? provider, int genre, Sort sort, int page, string today)
    {
        if (type is not ("movie" or "tv") || page < 1 || page > 500) throw new ArgumentOutOfRangeException(nameof(page));
        string date = type == "movie" ? "primary_release_date" : "first_air_date";
        string order = sort switch { Sort.ReleaseNewest => date + ".desc", Sort.ReleaseOldest => date + ".asc", Sort.Alphabetical => type == "movie" ? "original_title.asc" : "original_name.asc", Sort.AlphabeticalReverse => type == "movie" ? "original_title.desc" : "original_name.desc", Sort.Rating => "vote_average.desc", _ => "popularity.desc" };
        return $"{Metadata}/discover/{type}?language=en&include_adult=false&sort_by={order}&{date}.lte={today}&{date}.gte=1880-01-01&page={page}" +
            (provider == null ? "" : type == "movie" ? $"&with_watch_providers={provider.WatchId}&watch_region=US" : $"&with_networks={provider.Network}") +
            (genre > 0 ? $"&with_genres={genre}" : "") + (sort == Sort.Rating ? "&vote_count.gte=100" : "");
    }
    private async Task<JsonElement> Get(string address, CancellationToken ct)
    {
        using var response = await http.GetAsync(address, ct); response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct)); return doc.RootElement.Clone();
    }
    public static JsonElement NextData(string html)
    {
        var match = Regex.Match(html, "<script[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOptions.Singleline | RegexOptions.IgnoreCase);
        if (!match.Success) throw new InvalidDataException("Movy's catalog format changed.");
        using var doc = JsonDocument.Parse(match.Groups[1].Value); return doc.RootElement.Clone();
    }
    public static IReadOnlyList<Row> Rows(JsonElement data)
    {
        var rows = new List<Row>();
        void Collect(JsonElement e, int depth)
        {
            if (depth > 8) return;
            if (e.ValueKind == JsonValueKind.Array) { foreach (var child in e.EnumerateArray()) Collect(child, depth + 1); return; }
            if (e.ValueKind != JsonValueKind.Object) return;
            if (e.Get("movies").ValueKind == JsonValueKind.Array && e.Text("name").Length > 0) { Add(e.Text("name"), e.Get("movies")); return; }
            foreach (var property in e.EnumerateObject())
            {
                if (property.Value.ValueKind == JsonValueKind.Array && property.Value.Array().FirstOrDefault().Text("slug").Length > 0) Add(property.Name, property.Value);
                else Collect(property.Value, depth + 1);
            }
        }
        void Add(string name, JsonElement values)
        {
            var titles = values.Array().Select(x => Title.From(x)).OfType<Title>().DistinctBy(x => x.Slug).ToArray();
            if (titles.Length > 0) rows.Add(new(Humanize(name), titles));
        }
        Collect(data, 0); return rows.DistinctBy(x => x.Name).Take(24).ToArray();
    }
    public static string Humanize(string name) => name.ToLowerInvariant() switch
    {
        "netflix" => "Popular on Netflix", "amazon" or "prime" or "primevideo" => "Popular on Prime Video", "hbo" or "hbomax" => "Popular on HBO Max", "disney" => "Popular on Disney+",
        "popularmovies" => "Top Movies on Movy", "popularshowtv" => "Top Shows on Movy", "topratedmovie" => "Top Rated Movies", "topratedtv" => "Top Rated Series", "recent4k" => "Recently Added in 4K",
        _ => Regex.Replace(name, "([a-z])([A-Z])", "$1 $2").Replace('_', ' ')
    };
    public async Task<IReadOnlyList<Row>> Home(CancellationToken ct = default) => Rows(NextData(await http.GetStringAsync(Site + "/", ct)));
    public async Task<JsonElement> Details(Title title, CancellationToken ct = default)
    {
        if (!Security.TitlePath(title.Slug)) throw new ArgumentException("Invalid title.");
        return NextData(await http.GetStringAsync(Site + title.Root, ct)).Get("props").Get("pageProps").Get("details").Clone();
    }
    public async Task<Page> Library(string type, Provider? provider, int genre, Sort sort, int page, CancellationToken ct = default)
    {
        var json = await Get(LibraryUrl(type, provider, genre, sort, page, DateTime.Today.ToString("yyyy-MM-dd")), ct);
        return new(json.Get("results").Array().Select(x => Title.From(x, type)).OfType<Title>().ToArray(), page, (int)json.Number("total_pages"));
    }
    public async Task<IReadOnlyList<Genre>> Genres(string type, CancellationToken ct = default)
    {
        if (type is not ("movie" or "tv")) throw new ArgumentException("Invalid library.");
        return (await Get($"{Metadata}/genre/{type}/list?language=en", ct)).Get("genres").Array().Select(x => new Genre((int)x.Number("id"), x.Text("name"))).ToArray();
    }
    public async Task<IReadOnlyList<Title>> Search(string query, CancellationToken ct = default) =>
        (await Get($"{Metadata}/search/multi?query={Uri.EscapeDataString(query)}&language=en&page=1", ct)).Get("results").Array().Where(x => x.Text("media_type") is "movie" or "tv").Select(x => Title.From(x, x.Text("media_type"))).OfType<Title>().ToArray();
    public async Task<IReadOnlyList<JsonElement>> Episodes(long id, int season, CancellationToken ct = default)
    {
        try { return (await Get($"{Metadata}/tv/{id}/season/{season}?language=en", ct)).Get("episodes").Array().Select(x => x.Clone()).ToArray(); }
        catch (HttpRequestException e) when (e.StatusCode == HttpStatusCode.NotFound) { return []; }
    }
    public async Task<Title?> NextEpisode(Title completed, CancellationToken ct = default)
    {
        string[] parts = completed.Slug.Split('/'); if (!completed.IsSeries || parts.Length != 5 || !int.TryParse(parts[3], out int season) || !int.TryParse(parts[4], out int episode)) return null;
        var candidates = (await Episodes(completed.Id, season, ct)).Where(e => e.Number("episode_number") > episode).OrderBy(e => e.Number("episode_number")).ToArray();
        if (candidates.Length == 0) { season++; candidates = (await Episodes(completed.Id, season, ct)).Where(e => e.Number("episode_number") > 0).OrderBy(e => e.Number("episode_number")).ToArray(); }
        if (candidates.Length == 0) return null; var next = candidates[0]; string date = next.Text("air_date");
        if (!DateOnly.TryParseExact(date, "yyyy-MM-dd", out _)) return null;
        return completed with { Slug = $"/tv/{completed.Id}/{season}/{(int)next.Number("episode_number")}", Resume = 0, Duration = 0, NextEpisode = true, AvailableOn = date, Updated = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };
    }
    public static string CaptionEndpoint(string slug)
    {
        if (!Security.TitlePath(slug)) throw new ArgumentException("Invalid title."); string[] parts = slug.Split('/');
        return MovyConfig.Subtitles + "?id=" + parts[2] + (parts[1] == "tv" ? $"&season={(parts.Length == 5 ? parts[3] : "1")}&episode={(parts.Length == 5 ? parts[4] : "1")}" : "");
    }
    public async Task<IReadOnlyList<Caption>> Captions(string slug, CancellationToken ct = default)
    {
        var json = await Get(CaptionEndpoint(slug), ct);
        return json.Array().Where(x => Security.Https(x.Text("url")) && x.Text("format").ToLowerInvariant() is "srt" or "vtt").DistinctBy(x => x.Text("url"))
            .GroupBy(x => x.Text("language")).SelectMany(g => g.Take(3)).Take(60)
            .Select(x => new Caption(x.Text("url"), x.Text("display", x.Text("language", "Subtitles")) + (x.Text("release").Length > 0 ? " · " + x.Text("release")[..Math.Min(45, x.Text("release").Length)] : ""), x.Text("language"), x.Text("format"))).ToArray();
    }
}
