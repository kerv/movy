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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.*;

/** Native HLS player with remote media keys, resume, and track controls. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends android.app.Activity {
    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout toolbar;
    private LinearLayout controls;
    private PlaybackStore store;
    private final Handler timer=new Handler();
    private final Runnable checkpoint=new Runnable(){@Override public void run(){save();timer.postDelayed(this,10000);}};
    private boolean resumePlayback;
    private boolean refreshedSource;
    private FrameLayout root;
    private StreamResolver resolver;
    private PlaybackTracks available;
    private HashMap<String,String> requestHeaders;
    private String currentUrl;
    private View captionButton;
    private boolean captionLookupRunning,captionLookupFinished,captionLookupFailed;
    private int attachedCaptionCount;
    private AlertDialog captionLookupDialog;
    private String pendingSubtitleId;
    private FrameLayout sourceLoading;
    private TextView sourceStatus;
    private boolean sourceWasPlaying;
    private final PlaybackExitGuard exitGuard=new PlaybackExitGuard();
    private Toast exitHint;
    private boolean backKeyDown;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new PlaybackStore(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);view=new PlayerView(this);view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);view.setShowSubtitleButton(true);view.setControllerShowTimeoutMs(5000);root.addView(view);setContentView(root);
        toolbar=new LinearLayout(this);toolbar.setOrientation(LinearLayout.VERTICAL);toolbar.setPadding(28,14,28,14);toolbar.setBackgroundColor(0xcb090d16);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("title"));title.setTextColor(Color.WHITE);title.setTextSize(18);toolbar.addView(title,new LinearLayout.LayoutParams(-1,-2));
        controls=new LinearLayout(this);HorizontalScrollView controlScroll=new HorizontalScrollView(this);controlScroll.setHorizontalScrollBarEnabled(false);controlScroll.setSmoothScrollingEnabled(true);controlScroll.addView(controls);toolbar.addView(controlScroll);
        control("Sources",this::sources);control("Audio",()->tracks(C.TRACK_TYPE_AUDIO));control("Subtitles",()->tracks(C.TRACK_TYPE_TEXT));control("Quality",()->tracks(C.TRACK_TYPE_VIDEO));control("Speed",this::speed);
        String key=getIntent().getStringExtra("key");if(key!=null){new PlaybackStore(this).started(key);if(key.startsWith("/tv/"))control("Next episode",this::nextEpisode);}
        control("Back",this::onBackPressed);
        root.addView(toolbar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{toolbar.setVisibility(visibility);view.post(this::enableCaptionButton);});
        Uri stream=getIntent().getData();if(stream==null){finish();return;}
        HashMap<String,String> headers=new HashMap<>();java.io.Serializable value=getIntent().getSerializableExtra("headers");if(value instanceof Map)for(Object entry:((Map<?,?>)value).entrySet()){Map.Entry<?,?> e=(Map.Entry<?,?>)entry;if(e.getKey() instanceof String&&e.getValue() instanceof String)headers.put((String)e.getKey(),(String)e.getValue());}
        available=new PlaybackTracks(getIntent().getStringExtra("tracks"));
        player=new ExoPlayer.Builder(this).build();view.setPlayer(player);
        captionButton=view.findViewById(androidx.media3.ui.R.id.exo_subtitle);enableCaptionButton();
        player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException error){
            if(resolver!=null)return;
            if(!refreshedSource&&(error.errorCode==PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS||error.errorCode==PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE||error.errorCode==PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)){refreshedSource=true;refreshSource();return;}
            playbackError();
        }@Override public void onTracksChanged(Tracks tracks){applyPendingSubtitle(tracks);view.post(PlayerActivity.this::enableCaptionButton);}@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED)save();view.post(PlayerActivity.this::enableCaptionButton);}});
        long position=state!=null?state.getLong("position"):getIntent().getLongExtra("position",0);if(position>0)player.seekTo(position);
        prepare(stream,headers,position);view.requestFocus();timer.postDelayed(checkpoint,10000);
        if(available.subtitles.isEmpty())discoverCaptions(false);else captionLookupFinished=true;
    }
    private void control(String name,Runnable click){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setOnClickListener(v->click.run());controls.addView(b,new LinearLayout.LayoutParams(-2,-2));}
    private void speed(){String[] labels={"0.75×","Normal","1.25×","1.5×","2×"};float[] rates={.75f,1f,1.25f,1.5f,2f};new AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels,(d,w)->player.setPlaybackSpeed(rates[w])).show();}
    private void nextEpisode(){String[] parts=getIntent().getStringExtra("key").split("/");try{int s=Integer.parseInt(parts[3]),e=Integer.parseInt(parts[4]);setResult(RESULT_OK,new android.content.Intent().putExtra("season",s).putExtra("episode",e+1));finish();}catch(Exception ignored){}}
    private void prepare(Uri stream,HashMap<String,String> headers,long position){
        requestHeaders=headers;currentUrl=stream.toString();
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        MediaItem item=new MediaItem.Builder().setUri(stream).setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(available.subtitles).build();
        // DefaultMediaSourceFactory merges sidecar VTT/SRT media with HLS.
        // HlsMediaSource alone drops these external subtitle configurations.
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item));player.seekTo(position);player.prepare();player.play();
        attachedCaptionCount=available.subtitles.size();view.post(this::enableCaptionButton);
    }
    private void refreshSource(){
        resolveSource(available.server);
    }
    private void sources(){
        if(resolver!=null)return;
        List<String> names=new ArrayList<>();for(PlaybackTracks.Server server:available.servers)names.add((server.name.equals(available.server)?"✓ ":"")+server.label);
        names.add("Refresh current source");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Playback source"+(available.server.isEmpty()?"":" · "+available.server)).setItems(names.toArray(new String[0]),(d,w)->{refreshedSource=false;if(w<available.servers.size())resolveSource(available.servers.get(w).name);else refreshSource();}).setNegativeButton("Cancel",null).create();
        dialog.show();
    }
    private void showSourceLoading(String server){
        if(sourceLoading==null){sourceLoading=new FrameLayout(this);sourceLoading.setBackgroundColor(0xf5090d16);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(32,24,32,24);
            ProgressBar spinner=new ProgressBar(this);box.addView(spinner,new LinearLayout.LayoutParams(48,48));sourceStatus=new TextView(this);sourceStatus.setTextSize(20);sourceStatus.setTextColor(Color.WHITE);sourceStatus.setGravity(Gravity.CENTER);sourceStatus.setPadding(0,24,0,24);box.addView(sourceStatus);
            Button cancel=new Button(this);cancel.setText("Cancel source change");cancel.setAllCaps(false);cancel.setOnClickListener(v->cancelSource(true));box.addView(cancel);sourceLoading.addView(box,new FrameLayout.LayoutParams(-1,-1));root.addView(sourceLoading,new FrameLayout.LayoutParams(-1,-1));}
        sourceStatus.setText(server.isEmpty()?"Finding a fresh stream…":"Connecting to "+server+"…");sourceLoading.setVisibility(View.VISIBLE);sourceLoading.bringToFront();((LinearLayout)sourceLoading.getChildAt(0)).getChildAt(2).requestFocus();
    }
    private void resolveSource(String server){
        if(resolver!=null)return;String key=getIntent().getStringExtra("key");if(key==null){Toast.makeText(this,"No title is available to resolve.",Toast.LENGTH_SHORT).show();return;}
        long position=player.getCurrentPosition();sourceWasPlaying=player.getPlayWhenReady();player.pause();showSourceLoading(server);resolver=new StreamResolver(this,root);
        resolver.start(CatalogClient.SITE+key+"?play=true",server,new StreamResolver.Callback(){
            @Override public void progress(String message){sourceStatus.setText(message);}
            @Override public void ready(String url,HashMap<String,String> headers,String tracks){
                resolver=null;PlaybackTracks next=new PlaybackTracks(tracks);if(next.subtitles.isEmpty())next.subtitles.addAll(available.subtitles);
                String language="";for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<group.length;i++)if(group.isTrackSelected(i))language=group.getTrackFormat(i).language;
                TrackSelectionParameters.Builder selection=player.getTrackSelectionParameters().buildUpon().clearOverrides();if(language!=null&&!language.isEmpty())selection.setPreferredTextLanguage(language);player.setTrackSelectionParameters(selection.build());pendingSubtitleId=null;
                available=next;prepare(Uri.parse(url),headers,position);if(!sourceWasPlaying)player.pause();sourceLoading.setVisibility(View.GONE);view.requestFocus();
                if(available.subtitles.isEmpty()){captionLookupFinished=false;discoverCaptions(false);}
            }
            @Override public void failed(String message){resolver=null;sourceLoading.setVisibility(View.GONE);if(sourceWasPlaying)player.play();new AlertDialog.Builder(PlayerActivity.this).setTitle("Source unavailable").setMessage("This server did not return a playable stream. Your previous source and position have been kept.").setPositiveButton("Choose another",(d,w)->sources()).setNegativeButton("Keep current",null).show();}
        });
    }
    private void cancelSource(boolean restorePlayback){if(resolver!=null){resolver.close();resolver=null;}if(sourceLoading!=null)sourceLoading.setVisibility(View.GONE);if(restorePlayback&&player!=null&&sourceWasPlaying)player.play();if(restorePlayback)view.requestFocus();}
    @Override public void onBackPressed(){if(resolver!=null){exitGuard.reset();cancelSource(true);return;}if(exitGuard.press(android.os.SystemClock.uptimeMillis())){if(exitHint!=null)exitHint.cancel();finish();return;}if(view!=null){view.hideController();view.requestFocus();}if(toolbar!=null)toolbar.setVisibility(View.GONE);exitHint=Toast.makeText(this,"Press Back again to return to the title",Toast.LENGTH_SHORT);exitHint.show();}
    private void playbackError(){new AlertDialog.Builder(this).setTitle("Playback interrupted").setMessage("This source could not play. Request a fresh stream or return to the title.").setPositiveButton("Return to title",(d,w)->finish()).setNegativeButton("Refresh source",(d,w)->refreshSource()).show();}
    private void tracks(int type){
        if(type==C.TRACK_TYPE_TEXT&&available.subtitles.size()!=attachedCaptionCount){boolean playing=player.getPlayWhenReady();prepare(Uri.parse(currentUrl),requestHeaders,player.getCurrentPosition());if(!playing)player.pause();}
        if(type==C.TRACK_TYPE_VIDEO&&available.sources.size()>1){List<String> names=new ArrayList<>();for(PlaybackTracks.Source source:available.sources)names.add((source.url.equals(currentUrl)?"✓ ":"")+source.label);new AlertDialog.Builder(this).setTitle("Quality").setItems(names.toArray(new String[0]),(d,w)->{PlaybackTracks.Source source=available.sources.get(w);prepare(Uri.parse(source.url),requestHeaders,player.getCurrentPosition());}).show();return;}
        List<String> labels=new ArrayList<>();List<TrackSelectionOverride> options=new ArrayList<>();labels.add(type==C.TRACK_TYPE_TEXT?"Off":"Auto");options.add(null);
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==type)for(int i=0;i<group.length;i++){if(!group.isTrackSupported(i))continue;Format f=group.getTrackFormat(i);String label=type==C.TRACK_TYPE_VIDEO?(f.height>0?f.height+"p":"Video"):PlaybackTracks.languageLabel(f.label,f.language,type==C.TRACK_TYPE_AUDIO?"Original audio (default)":"Subtitles "+(i+1));labels.add((group.isTrackSelected(i)?"✓ ":"")+label);options.add(new TrackSelectionOverride(group.getMediaTrackGroup(),i));}
        if(options.size()==1){
            if(type==C.TRACK_TYPE_TEXT&&!available.subtitles.isEmpty()){
                for(MediaItem.SubtitleConfiguration config:available.subtitles){labels.add(config.label==null?"Subtitles":config.label);options.add(null);}
                new AlertDialog.Builder(this).setTitle("Subtitles").setItems(labels.toArray(new String[0]),(d,w)->{pendingSubtitleId=w>0?available.subtitles.get(w-1).id:null;TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,w==0);if(w>0)builder.setPreferredTextLanguage(available.subtitles.get(w-1).language).setSelectUndeterminedTextLanguage(true);player.setTrackSelectionParameters(builder.build());applyPendingSubtitle(player.getCurrentTracks());}).show();return;
            }
            if(type==C.TRACK_TYPE_TEXT){
                if(!captionLookupFinished||captionLookupRunning){discoverCaptions(true);return;}
                new AlertDialog.Builder(this).setTitle("Captions").setMessage(captionLookupFailed?"Couldn’t reach Movy’s caption service. Please try again.":"No captions were found for this title at the moment.").setPositiveButton("Search again",(d,w)->{captionLookupFinished=false;discoverCaptions(true);}).setNegativeButton("Cancel",null).show();return;
            }
            new AlertDialog.Builder(this).setTitle("Audio").setMessage("This source provides its original audio. No additional audio languages are advertised.").setPositiveButton("OK",null).show();return;
        }
        String title=type==C.TRACK_TYPE_TEXT?"Subtitles":type==C.TRACK_TYPE_AUDIO?"Audio":"Quality (available in this source)";
        new AlertDialog.Builder(this).setTitle(title).setItems(labels.toArray(new String[0]),(d,w)->{if(type==C.TRACK_TYPE_TEXT)pendingSubtitleId=null;TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,w==0&&type==C.TRACK_TYPE_TEXT);if(options.get(w)!=null)builder.setOverrideForType(options.get(w));player.setTrackSelectionParameters(builder.build());}).show();
    }
    private void applyPendingSubtitle(Tracks tracks){
        if(pendingSubtitleId==null)return;
        for(Tracks.Group group:tracks.getGroups())if(group.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<group.length;i++)if(pendingSubtitleId.equals(group.getTrackFormat(i).id)&&group.isTrackSupported(i)){
            pendingSubtitleId=null;player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),i)).build());return;
        }
    }
    private void enableCaptionButton(){if(isDestroyed()||captionButton==null)return;captionButton.setVisibility(View.VISIBLE);captionButton.setEnabled(true);captionButton.setAlpha(1);captionButton.setContentDescription("Closed captions");captionButton.setOnClickListener(v->tracks(C.TRACK_TYPE_TEXT));}
    private void discoverCaptions(boolean showMenu){
        if(showMenu&&(captionLookupDialog==null||!captionLookupDialog.isShowing())){captionLookupDialog=new AlertDialog.Builder(this).setTitle("Finding captions").setMessage("Searching Movy’s subtitle service…").setNegativeButton("Cancel",null).create();captionLookupDialog.show();}
        if(captionLookupRunning)return;captionLookupRunning=true;captionLookupFailed=false;
        SubtitleLookup.fetch(getIntent().getStringExtra("key"),(files,error)->{
            if(isDestroyed())return;captionLookupRunning=false;captionLookupFinished=true;captionLookupFailed=error!=null;
            if(error==null&&available.subtitles.isEmpty())try{org.json.JSONObject data=new org.json.JSONObject();data.put("subtitles",files);available.subtitles.addAll(new PlaybackTracks(data.toString()).subtitles);}catch(Exception ignored){captionLookupFailed=true;}
            boolean open=captionLookupDialog!=null&&captionLookupDialog.isShowing();if(captionLookupDialog!=null){captionLookupDialog.dismiss();captionLookupDialog=null;}enableCaptionButton();
            if(open)tracks(C.TRACK_TYPE_TEXT);
        });
    }
    private void save(){if(player==null)return;String key=getIntent().getStringExtra("key");if(key!=null){boolean completed=store.progress(key,getIntent().getStringExtra("title"),getIntent().getStringExtra("poster"),player.getCurrentPosition(),player.getDuration());if(completed&&key.startsWith("/tv/"))try{CatalogClient.Title title=new CatalogClient.Title(new org.json.JSONObject().put("id",Long.parseLong(key.split("/")[2])).put("slug",key).put("title",getIntent().getStringExtra("title")).put("poster",getIntent().getStringExtra("poster")));NextEpisodeFinder.find(title,(next,error)->{if(error==null)store.queueNext(title,next);});}catch(Exception ignored){}}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();if(key==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&!backKeyDown){backKeyDown=true;onBackPressed();}else if(event.getAction()==KeyEvent.ACTION_UP)backKeyDown=false;return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN)exitGuard.reset();if(player==null)return super.dispatchKeyEvent(event);
        if(key==KeyEvent.KEYCODE_CAPTIONS){if(resolver==null&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)tracks(C.TRACK_TYPE_TEXT);return true;}
        boolean media=key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||key==KeyEvent.KEYCODE_MEDIA_REWIND;
        if(!media)return super.dispatchKeyEvent(event);if(resolver!=null)return true;if(event.getAction()==KeyEvent.ACTION_DOWN){switch(key){case KeyEvent.KEYCODE_MEDIA_PLAY:player.play();break;case KeyEvent.KEYCODE_MEDIA_PAUSE:player.pause();break;case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:if(player.isPlaying())player.pause();else player.play();break;case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:player.seekTo(player.getCurrentPosition()+10000);break;case KeyEvent.KEYCODE_MEDIA_REWIND:player.seekTo(Math.max(0,player.getCurrentPosition()-10000));break;}}return true;
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(player!=null)state.putLong("position",player.getCurrentPosition());}
    @Override protected void onStop(){super.onStop();exitGuard.reset();backKeyDown=false;if(exitHint!=null)exitHint.cancel();if(captionLookupDialog!=null){captionLookupDialog.dismiss();captionLookupDialog=null;}save();if(player!=null){resumePlayback=resolver!=null?sourceWasPlaying:player.getPlayWhenReady();cancelSource(false);player.pause();}}
    @Override protected void onStart(){super.onStart();if(player!=null&&resumePlayback)player.play();}
    @Override protected void onDestroy(){timer.removeCallbacksAndMessages(null);save();if(player!=null){view.setPlayer(null);player.release();player=null;}super.onDestroy();}
}
