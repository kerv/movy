package sx.movy.tv;

import android.os.Handler;
import android.os.Looper;

/** Only submit the most recent text after a short typing pause. */
final class SearchDebouncer {
    private final Handler handler=new Handler(Looper.getMainLooper());
    interface Callback {void accept(String value);}
    private final Callback search;
    private Runnable pending;
    SearchDebouncer(Callback search){this.search=search;}
    void submit(String value){cancel();String query=value.trim();if(query.length()<2)return;pending=()->{pending=null;search.accept(query);};handler.postDelayed(pending,650);}
    void cancel(){if(pending!=null){handler.removeCallbacks(pending);pending=null;}}
}
