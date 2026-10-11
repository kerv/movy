package sx.movy.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small read-only client for Movy's server-rendered catalogue. It deliberately
 * stores metadata only; stream URLs are never fetched while browsing. */
final class CatalogClient {
    static final String SITE = "https://www.movy.sx";
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Result<T> { void accept(T value, Exception error); }

    static final class Title {
        final long id;
        final String title, slug, poster, backdrop, year, rating;
        Title(JSONObject item) {
            id = item.optLong("id");
            title = item.optString("title", item.optString("name", "Untitled"));
            slug = item.optString("slug");
            poster = item.optString("poster", item.optString("image"));
            backdrop = item.optString("image", item.optString("background", poster));
            String date = item.optString("release_date", item.optString("first_air_date", ""));
            year = date.length() >= 4 ? date.substring(0, 4) : "";
            rating = item.has("rating") ? String.valueOf(item.optDouble("rating")) : "";
        }
    }

    static final class Row {
        final String name;
        final List<Title> titles;
        Row(String name, List<Title> titles) { this.name = name; this.titles = titles; }
    }

    static void home(Result<List<Row>> callback) { load("/", callback, true); }
    static void search(String query, Result<List<Row>> callback) {
        IO.execute(() -> {
            try {
                // This is the same read-only metadata endpoint used by Movy's
                // own search panel, not a scrape of the search UI.
                String q = URLEncoder.encode(query, "UTF-8");
                JSONObject response = new JSONObject(get("https://db.wecollege.net/3/search/multi?query=" + q + "&language=en&page=1"));
                JSONArray results = response.optJSONArray("results");
                ArrayList<Title> titles = new ArrayList<>();
                if (results != null) for (int i = 0; i < results.length(); i++) {
                    JSONObject source = results.optJSONObject(i); if (source == null) continue;
                    String type = source.optString("media_type"); if (!"movie".equals(type) && !"tv".equals(type)) continue;
                    JSONObject item = new JSONObject(); item.put("id", source.optLong("id")); item.put("title", "movie".equals(type) ? source.optString("title") : source.optString("name"));
                    item.put("slug", "/" + type + "/" + source.optLong("id")); item.put("poster", imageUrl(source.optString("poster_path", source.optString("backdrop_path"))));
                    item.put("image", imageUrl(source.optString("backdrop_path", source.optString("poster_path")))); item.put("rating", source.optDouble("vote_average")); item.put("release_date", source.optString("release_date", source.optString("first_air_date")));
                    titles.add(new Title(item));
                }
                List<Row> rows = java.util.Collections.singletonList(new Row("Search results", titles));
                MAIN.post(() -> callback.accept(rows, null));
            } catch (Exception e) { MAIN.post(() -> callback.accept(null, e)); }
        });
    }

    private static String imageUrl(String path) {
        return path == null || path.isEmpty() ? "" : "https://wsrv.nl/?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw342" + path.replace("/", "%2F") + "&output=webp&q=55";
    }

    private static void load(final String path, final Result<List<Row>> callback, final boolean grouped) {
        IO.execute(() -> {
            try {
                JSONObject data = nextData(get(SITE + path));
                List<Row> rows = grouped ? rowsFrom(data) : searchRows(data);
                MAIN.post(() -> callback.accept(rows, null));
            } catch (Exception e) {
                MAIN.post(() -> callback.accept(null, e));
            }
        });
    }

    private static List<Row> rowsFrom(JSONObject root) {
        ArrayList<Row> rows = new ArrayList<>();
        collectRows(root, rows, 0);
        // The page contains several similarly shaped sections. Keep a useful
        // television-sized set, de-duplicating labels as server data evolves.
        LinkedHashMap<String, Row> unique = new LinkedHashMap<>();
        for (Row row : rows) if (row.titles.size() >= 4 && !unique.containsKey(row.name)) unique.put(row.name, row);
        // Do not silently drop provider rails (Netflix, Prime, HBO, etc.) if
        // Movy adds more of them than the usual homepage set.
        return new ArrayList<>(unique.values()).subList(0, Math.min(24, unique.size()));
    }

    private static List<Row> searchRows(JSONObject root) {
        ArrayList<Title> found = new ArrayList<>();
        collectTitles(root, found, 0);
        Map<String, Title> unique = new LinkedHashMap<>();
        for (Title title : found) if (!title.slug.isEmpty()) unique.put(title.slug, title);
        return java.util.Collections.singletonList(new Row("Results", new ArrayList<>(unique.values())));
    }

    private static void collectRows(Object value, List<Row> out, int depth) {
        if (depth > 7 || value == null) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            // Homepage sections are shaped as { name: "trendingMovies",
            // movies: [...] }. Use the section name, never the generic key.
            JSONArray sectionMovies = object.optJSONArray("movies");
            if (sectionMovies != null && sectionMovies.length() > 0 && object.has("name")) {
                ArrayList<Title> titles = new ArrayList<>();
                for (int i = 0; i < sectionMovies.length(); i++) {
                    JSONObject item = sectionMovies.optJSONObject(i); if (item != null) titles.add(new Title(item));
                }
                out.add(new Row(humanize(object.optString("name")), titles));
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next(); Object child = object.opt(key);
                if (child instanceof JSONArray && ((JSONArray) child).length() > 0 && ((JSONArray) child).opt(0) instanceof JSONObject
                        && ((JSONObject) ((JSONArray) child).opt(0)).has("slug")) {
                    ArrayList<Title> titles = new ArrayList<>();
                    for (int i = 0; i < ((JSONArray) child).length(); i++) titles.add(new Title(((JSONArray) child).optJSONObject(i)));
                    out.add(new Row(humanize(key), titles));
                } else collectRows(child, out, depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value; for (int i = 0; i < a.length(); i++) collectRows(a.opt(i), out, depth + 1);
        }
    }
    private static void collectTitles(Object value, List<Title> out, int depth) {
        if (depth > 8 || value == null) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("slug") && (object.has("title") || object.has("name"))) out.add(new Title(object));
            java.util.Iterator<String> keys = object.keys(); while (keys.hasNext()) collectTitles(object.opt(keys.next()), out, depth + 1);
        } else if (value instanceof JSONArray) { JSONArray a = (JSONArray) value; for (int i=0;i<a.length();i++) collectTitles(a.opt(i), out, depth+1); }
    }
    private static String humanize(String key) {
        String normal = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').trim();
        if ("netflix".equalsIgnoreCase(key)) return "Top Netflix";
        if ("amazon".equalsIgnoreCase(key) || "prime".equalsIgnoreCase(key)) return "Top Prime Video";
        if ("hbo".equalsIgnoreCase(key)) return "Top HBO";
        if ("disney".equalsIgnoreCase(key)) return "Top Disney+";
        if ("hulu".equalsIgnoreCase(key)) return "Top Hulu";
        if ("apple".equalsIgnoreCase(key)) return "Top Apple TV+";
        if ("paramount".equalsIgnoreCase(key)) return "Top Paramount+";
        if ("popularMovies".equals(key)) return "Top Movies on Movy";
        if ("popularShowTV".equals(key)) return "Top Shows on Movy";
        return normal.substring(0, 1).toUpperCase() + normal.substring(1);
    }

    private static JSONObject nextData(String html) throws Exception {
        String marker = "<script id=\"__NEXT_DATA__\"";
        int start = html.indexOf(marker); if (start < 0) throw new IllegalStateException("Movy catalogue format changed");
        start = html.indexOf('>', start) + 1; int end = html.indexOf("</script>", start);
        return new JSONObject(html.substring(start, end));
    }
    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV) MovyTV/2.0");
        // Login occurs in the scoped account WebView. Android's CookieManager
        // is process-wide, so forwarding its site cookie makes personalized
        // metadata (Continue Watching/watchlist) available to this read-only
        // native client without storing credentials ourselves.
        String cookie = CookieManager.getInstance().getCookie(address);
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder b = new StringBuilder(); String line; while ((line = r.readLine()) != null) b.append(line); return b.toString();
        } finally { c.disconnect(); }
    }
    static void image(final String url, final Result<Bitmap> callback) {
        if (url == null || url.isEmpty()) return;
        IO.execute(() -> { try (InputStream in = new URL(url).openStream()) { Bitmap b = BitmapFactory.decodeStream(in); MAIN.post(() -> callback.accept(b, null)); }
            catch (Exception e) { MAIN.post(() -> callback.accept(null, e)); } });
    }
}
