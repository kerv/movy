package sx.movy.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Device-local resume positions and saved titles, separate from Movy accounts. */
final class PlaybackStore {
    static final int RECENT_LIMIT=40;
    private final SharedPreferences prefs;
    PlaybackStore(Context context) { prefs = context.getSharedPreferences("native_library", Context.MODE_PRIVATE); }
    long position(String key) { return prefs.getLong("position:" + key, 0); }
    boolean saved(String slug) { return prefs.contains("saved:" + slug); }
    String resumeKey(CatalogClient.Title title) {
        for(CatalogClient.Title watched:titles("resume:"))if(watched.id==title.id&&watched.isSeries()==title.isSeries())return watched.slug;
        for(CatalogClient.Title watched:titles("history:"))if(watched.id==title.id&&watched.isSeries()==title.isSeries()&&position(watched.slug)>0)return watched.slug;
        return title.slug;
    }
    void toggle(CatalogClient.Title title) {
        String key = "saved:" + title.slug;
        if (saved(title.slug)) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, encode(title)).apply();
    }
    boolean progress(String key, String title, String poster, long position, long duration) {
        SharedPreferences.Editor edit = prefs.edit().putLong("position:" + key, position);
        boolean complete=duration>0&&position>=Math.max(duration*.95,duration-60000),firstComplete=complete&&!prefs.getBoolean("completed:"+key,false);
        String root=titleKey(key);
        if (complete) edit.remove("resume:" + key).putLong("position:" + key, 0).putBoolean("completed:"+key,true).putBoolean("completed_title:"+root,true);
        if (position > 15000) {
            try {
                JSONObject j = new JSONObject(); j.put("title", title); j.put("poster", poster);
                j.put("slug", key); j.put("id", idFromKey(key)); j.put("updated", System.currentTimeMillis());j.put("resumeMs",complete?0:position);j.put("durationMs",duration);
                edit.putString("history:"+root,j.toString());
                if(!complete){if(key.startsWith("/tv/"))for(String old:prefs.getAll().keySet())if(old.startsWith("resume:/tv/")&&titleKey(old.substring(7)).equals(root))edit.remove(old);
                    edit.putString("resume:" + key, j.toString()).putBoolean("completed:"+key,false).putBoolean("completed_title:"+root,false).remove("next:"+root).remove("pending_next:"+root);
                }else if(firstComplete&&key.startsWith("/tv/"))edit.putString("pending_next:"+root,j.toString());
            } catch (Exception ignored) { }
        }
        edit.apply();
        return firstComplete;
    }
    static String titleKey(String key){String[] p=key.split("/");return p.length>=3?"/"+p[1]+"/"+p[2]:key;}
    boolean completedTitle(CatalogClient.Title title){return prefs.getBoolean("completed_title:"+titleKey(title.slug),false);}
    void started(String key){prefs.edit().remove("hidden:local:"+titleKey(key)).apply();}
    boolean dismissed(CatalogClient.Title title,String scope){return prefs.getBoolean("hidden:"+scope+":"+titleKey(title.slug),false);}
    void dismiss(CatalogClient.Title title,String scope){String root=titleKey(title.slug);SharedPreferences.Editor edit=prefs.edit().putBoolean("hidden:local:"+root,true).putBoolean("hidden:"+scope+":"+root,true).remove("next:"+root).remove("pending_next:"+root);
        for(String key:prefs.getAll().keySet())if(key.startsWith("resume:")&&titleKey(key.substring(7)).equals(root))edit.remove(key);edit.apply();}
    void queueNext(CatalogClient.Title completed,NextEpisodeFinder.Next next){String root=titleKey(completed.slug);try{JSONObject pending=new JSONObject(prefs.getString("pending_next:"+root,"{}"));if(!completed.slug.equals(pending.optString("slug")))return;SharedPreferences.Editor edit=prefs.edit().remove("pending_next:"+root);if(next!=null){JSONObject item=new JSONObject(encode(completed));item.put("slug",next.key);item.put("availableOn",next.date);item.put("nextEpisode",true);item.put("resumeMs",0);edit.putString("next:"+root,item.toString());}edit.apply();}catch(Exception ignored){}}
    List<CatalogClient.Title> titles(String prefix) {
        ArrayList<JSONObject> items = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(prefix)) {
            try { items.add(new JSONObject(prefs.getString(key, "{}"))); } catch (Exception ignored) { }
        }
        Collections.sort(items, (a,b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
        ArrayList<CatalogClient.Title> titles = new ArrayList<>();
        String today=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(new java.util.Date());
        for (JSONObject item : items) {CatalogClient.Title title=new CatalogClient.Title(item);if((prefix.equals("resume:")||prefix.equals("next:"))&&dismissed(title,"local"))continue;if(prefix.equals("next:")&&!title.availableOn.isEmpty()&&title.availableOn.compareTo(today)>0)continue;titles.add(title);if(!prefix.equals("saved:")&&titles.size()>=RECENT_LIMIT)break;}
        return titles;
    }
    private static long idFromKey(String key) { try { return Long.parseLong(key.split("/")[2]); } catch (Exception e) { return 0; } }
    private static String encode(CatalogClient.Title title) {
        try { JSONObject j=new JSONObject(); j.put("id",title.id); j.put("title",title.title); j.put("slug",title.slug); j.put("poster",title.poster); j.put("image",title.backdrop); j.put("rating",title.rating); j.put("updated",System.currentTimeMillis()); return j.toString(); }
        catch(Exception e) { return "{}"; }
    }
}
