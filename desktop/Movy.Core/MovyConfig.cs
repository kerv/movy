using System.Text.Json;
namespace Movy.Core;
public static class MovyConfig
{
    private static readonly JsonElement Config=Read();
    private static JsonElement Read(){using var input=typeof(MovyConfig).Assembly.GetManifestResourceStream("movy-config.json")!;using var doc=JsonDocument.Parse(input);return doc.RootElement.Clone();}
    public static string Site=>Config.Text("site");
    public static string Metadata=>Config.Text("metadata");
    public static string Account=>Config.Text("account");
    public static string Subtitles=>Config.Text("subtitles");
    public static Provider[] Providers=>Config.Get("providers").Array().Select(p=>new Provider(p.Text("name"),(int)p.Number("network"),(int)p.Number("watchId"),p.Text("color"))).ToArray();
}
