package sx.movy.tv;

import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;
import java.util.*;

/** Native HLS player with remote media keys, resume, and track controls. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends android.app.Activity {
    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout toolbar;
    private PlaybackStore store;
    private final Handler timer=new Handler();
    private final Runnable checkpoint=new Runnable(){@Override public void run(){save();timer.postDelayed(this,10000);}};
    private boolean resumePlayback;
    private boolean refreshedSource;
    private FrameLayout root;
    private StreamResolver resolver;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new PlaybackStore(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);view=new PlayerView(this);view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);view.setShowSubtitleButton(true);view.setControllerShowTimeoutMs(5000);root.addView(view);setContentView(root);
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(28,14,28,14);toolbar.setBackgroundColor(0xcb090d16);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("title"));title.setTextColor(Color.WHITE);title.setTextSize(18);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        control("Audio",()->tracks(C.TRACK_TYPE_AUDIO));control("Subtitles",()->tracks(C.TRACK_TYPE_TEXT));control("Quality",()->tracks(C.TRACK_TYPE_VIDEO));control("Speed",this::speed);
        String key=getIntent().getStringExtra("key");if(key!=null&&key.startsWith("/tv/"))control("Next episode",this::nextEpisode);
        control("Back",this::finish);
        root.addView(toolbar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->toolbar.setVisibility(visibility));
        Uri stream=getIntent().getData();if(stream==null){finish();return;}
        HashMap<String,String> headers=new HashMap<>();java.io.Serializable value=getIntent().getSerializableExtra("headers");if(value instanceof Map)for(Object entry:((Map<?,?>)value).entrySet()){Map.Entry<?,?> e=(Map.Entry<?,?>)entry;if(e.getKey() instanceof String&&e.getValue() instanceof String)headers.put((String)e.getKey(),(String)e.getValue());}
        player=new ExoPlayer.Builder(this).build();view.setPlayer(player);
        player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException error){
            if(!refreshedSource&&(error.errorCode==PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS||error.errorCode==PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE||error.errorCode==PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)){refreshedSource=true;refreshSource();return;}
            playbackError();
        }});
        long position=state!=null?state.getLong("position"):getIntent().getLongExtra("position",0);if(position>0)player.seekTo(position);
        prepare(stream,headers,position);view.requestFocus();timer.postDelayed(checkpoint,10000);
    }
    private void control(String name,Runnable click){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setOnClickListener(v->click.run());toolbar.addView(b,new LinearLayout.LayoutParams(-2,-2));}
    private void speed(){String[] labels={"0.75×","Normal","1.25×","1.5×","2×"};float[] rates={.75f,1f,1.25f,1.5f,2f};new AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels,(d,w)->player.setPlaybackSpeed(rates[w])).show();}
    private void nextEpisode(){String[] parts=getIntent().getStringExtra("key").split("/");try{int s=Integer.parseInt(parts[3]),e=Integer.parseInt(parts[4]);setResult(RESULT_OK,new android.content.Intent().putExtra("season",s).putExtra("episode",e+1));finish();}catch(Exception ignored){}}
    private void prepare(Uri stream,HashMap<String,String> headers,long position){
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player.setMediaSource(new HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(stream)));player.seekTo(position);player.prepare();player.play();
    }
    private void refreshSource(){
        if(resolver!=null)return;String key=getIntent().getStringExtra("key");if(key==null){playbackError();return;}
        long position=player.getCurrentPosition();Toast.makeText(this,"Refreshing the stream…",Toast.LENGTH_SHORT).show();resolver=new StreamResolver(this,root);
        resolver.start(CatalogClient.SITE+key+"?play=true",new StreamResolver.Callback(){
            @Override public void ready(String url,HashMap<String,String> headers){resolver=null;prepare(Uri.parse(url),headers,position);}
            @Override public void failed(String message){resolver=null;playbackError();}
        });
    }
    private void playbackError(){new AlertDialog.Builder(this).setTitle("Playback interrupted").setMessage("This source could not play. Request a fresh stream or return to the title.").setPositiveButton("Return to title",(d,w)->finish()).setNegativeButton("Refresh source",(d,w)->refreshSource()).show();}
    private void tracks(int type){
        List<String> labels=new ArrayList<>();List<TrackSelectionOverride> options=new ArrayList<>();labels.add(type==C.TRACK_TYPE_TEXT?"Off":"Auto");options.add(null);
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==type)for(int i=0;i<group.length;i++){if(!group.isTrackSupported(i))continue;Format f=group.getTrackFormat(i);String label=type==C.TRACK_TYPE_VIDEO?(f.height>0?f.height+"p":"Video"):(f.label!=null?f.label:f.language!=null?new Locale(f.language).getDisplayLanguage():"Track "+(i+1));labels.add(label);options.add(new TrackSelectionOverride(group.getMediaTrackGroup(),i));}
        String title=type==C.TRACK_TYPE_TEXT?"Subtitles":type==C.TRACK_TYPE_AUDIO?"Audio":"Quality (available in this source)";
        new AlertDialog.Builder(this).setTitle(title).setItems(labels.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,w==0&&type==C.TRACK_TYPE_TEXT);if(options.get(w)!=null)builder.setOverrideForType(options.get(w));player.setTrackSelectionParameters(builder.build());}).show();
    }
    private void save(){if(player==null)return;String key=getIntent().getStringExtra("key");if(key!=null)store.progress(key,getIntent().getStringExtra("title"),getIntent().getStringExtra("poster"),player.getCurrentPosition(),player.getDuration());}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(player==null)return super.dispatchKeyEvent(event);int key=event.getKeyCode();boolean media=key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||key==KeyEvent.KEYCODE_MEDIA_REWIND;if(!media)return super.dispatchKeyEvent(event);if(event.getAction()==KeyEvent.ACTION_DOWN){switch(key){case KeyEvent.KEYCODE_MEDIA_PLAY:player.play();break;case KeyEvent.KEYCODE_MEDIA_PAUSE:player.pause();break;case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:if(player.isPlaying())player.pause();else player.play();break;case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:player.seekTo(player.getCurrentPosition()+10000);break;case KeyEvent.KEYCODE_MEDIA_REWIND:player.seekTo(Math.max(0,player.getCurrentPosition()-10000));break;}}return true;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(player!=null)state.putLong("position",player.getCurrentPosition());}
    @Override protected void onStop(){super.onStop();if(resolver!=null){resolver.close();resolver=null;}save();if(player!=null){resumePlayback=player.getPlayWhenReady();player.pause();}}
    @Override protected void onStart(){super.onStart();if(player!=null&&resumePlayback)player.play();}
    @Override protected void onDestroy(){timer.removeCallbacksAndMessages(null);save();if(player!=null){view.setPlayer(null);player.release();player=null;}super.onDestroy();}
}
