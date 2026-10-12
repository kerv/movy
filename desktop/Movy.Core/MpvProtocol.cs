using System.Text.Json;
namespace Movy.Core;
public static class MpvProtocol
{
    public static string Command(long id, params object[] command) => JsonSerializer.Serialize(new { command, request_id = id });
    public static string Load(long id, string url, double position, bool paused)
    {
        if (!Security.Https(url) || !double.IsFinite(position) || position < 0) throw new ArgumentException("Invalid stream.");
        return Command(id, "loadfile", url, "replace", -1, new Dictionary<string, string> { ["start"] = position.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture), ["pause"] = paused ? "yes" : "no" });
    }
    public static IReadOnlyDictionary<string,string> HeadersFor(ResolvedStream stream, string newUrl) => stream.Headers.Where(pair =>
        pair.Key.Equals("Origin", StringComparison.OrdinalIgnoreCase) || pair.Key.Equals("Referer", StringComparison.OrdinalIgnoreCase) || pair.Key.Equals("User-Agent", StringComparison.OrdinalIgnoreCase) ||
        pair.Key.Equals("Cookie", StringComparison.OrdinalIgnoreCase) && new Uri(stream.Url).Host == new Uri(newUrl).Host)
        .Where(pair => !pair.Value.Contains('\r') && !pair.Value.Contains('\n')).ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.OrdinalIgnoreCase);
}
