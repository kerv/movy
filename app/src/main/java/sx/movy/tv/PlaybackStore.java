package sx.movy.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Device-local resume positions and saved titles, separate from Movy accounts. */
final class PlaybackStore {
    private final SharedPreferences prefs;
    PlaybackStore(Context context) { prefs = context.getSharedPreferences("native_library", Context.MODE_PRIVATE); }
    long position(String key) { return prefs.getLong("position:" + key, 0); }
    boolean saved(String slug) { return prefs.contains("saved:" + slug); }
    String resumeKey(CatalogClient.Title title) {
        for(CatalogClient.Title watched:titles("resume:"))if(watched.id==title.id&&watched.isSeries()==title.isSeries())return watched.slug;
        return title.slug;
    }
    void toggle(CatalogClient.Title title) {
        String key = "saved:" + title.slug;
        if (saved(title.slug)) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, encode(title)).apply();
    }
    void progress(String key, String title, String poster, long position, long duration) {
        SharedPreferences.Editor edit = prefs.edit().putLong("position:" + key, position);
        if (duration > 0 && position >= duration - 60000) edit.remove("resume:" + key).putLong("position:" + key, 0);
        else if (position > 15000) {
            try {
                JSONObject j = new JSONObject(); j.put("title", title); j.put("poster", poster);
                j.put("slug", key); j.put("id", idFromKey(key)); j.put("updated", System.currentTimeMillis());
                edit.putString("resume:" + key, j.toString());
            } catch (Exception ignored) { }
        }
        edit.apply();
    }
    List<CatalogClient.Title> titles(String prefix) {
        ArrayList<JSONObject> items = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(prefix)) {
            try { items.add(new JSONObject(prefs.getString(key, "{}"))); } catch (Exception ignored) { }
        }
        Collections.sort(items, (a,b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
        ArrayList<CatalogClient.Title> titles = new ArrayList<>();
        for (JSONObject item : items) titles.add(new CatalogClient.Title(item));
        return titles;
    }
    private static long idFromKey(String key) { try { return Long.parseLong(key.split("/")[2]); } catch (Exception e) { return 0; } }
    private static String encode(CatalogClient.Title title) {
        try { JSONObject j=new JSONObject(); j.put("id",title.id); j.put("title",title.title); j.put("slug",title.slug); j.put("poster",title.poster); j.put("image",title.backdrop); j.put("rating",title.rating); j.put("updated",System.currentTimeMillis()); return j.toString(); }
        catch(Exception e) { return "{}"; }
    }
}
