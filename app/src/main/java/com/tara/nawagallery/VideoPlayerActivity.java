package com.tara.nawagallery;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoPlayerActivity extends Activity {
    public static final String EXTRA_URI="video_uri", EXTRA_NAME="video_name", EXTRA_MIME="video_mime", EXTRA_PATH="video_path";
    private static final String PREFS_UI="video_player_ui";
    private static final int REQUEST_TRASH=120, REQUEST_SUBTITLE=121, REQUEST_TREE=122;
    private static final String PREFS="video_player_positions";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Uri uri; private String name,mime; private FrameLayout root; private VideoView video;
    private MediaPlayer player; private ProgressBar loading; private LinearLayout top,center,bottom;
    private TextView favorite,play,elapsed,duration,speed,unlock,title,subtitle,gestureFeedback; private SeekBar seek;
    private AudioManager audio; private GestureDetector gestures; private ScaleGestureDetector scaleGestures;
    private boolean dragging,visible=true,locked,repeatVideo; private float playbackSpeed=1f,zoom=1f;
    private int displayMode; private long abStart=-1,abEnd=-1; private String videoPath; private boolean userRotated;
    private List<SubtitleParser.Cue> subtitles=new ArrayList<>();
    private SubtitleTranslator translator; private String subtitleLanguage; private boolean subtitlesHidden;
    private ThemePrefs.ThemeStyle theme; private LinearLayout listPanel; private ListView cueList; private CueAdapter cueAdapter; private TextView listStatus;
    private int shownCue=-2; private long listTouchedAt;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();

    private final Runnable ticker=new Runnable(){ @Override public void run(){
        if(video!=null&&video.getDuration()>0&&!dragging){ int p=video.getCurrentPosition(); if(abEnd>abStart&&p>=abEnd){video.seekTo((int)abStart);p=(int)abStart;} seek.setMax(video.getDuration()); seek.setProgress(p); elapsed.setText(time(p)); duration.setText(time(video.getDuration())); play.setText(video.isPlaying()?"Ⅱ":"▶"); }
        handler.postDelayed(this,500);
    }};
    private final Runnable subtitleTicker=new Runnable(){ @Override public void run(){
        if(video!=null&&!subtitles.isEmpty()) updateSubtitle(video.getCurrentPosition());
        handler.postDelayed(this,100);
    }};
    private final Runnable autoHide=()->{if(!locked&&video!=null&&video.isPlaying())showControls(false);};
    private final Runnable hideGestureFeedback=()->gestureFeedback.animate().alpha(0f).setDuration(130).withEndAction(()->gestureFeedback.setVisibility(View.GONE)).start();

    @Override protected void onCreate(Bundle state){ super.onCreate(state); if(!AppIntegrity.verifyOrClose(this))return;
        Intent intent=getIntent(); String value=intent.getStringExtra(EXTRA_URI);
        if(value!=null){uri=Uri.parse(value); name=intent.getStringExtra(EXTRA_NAME); mime=intent.getStringExtra(EXTRA_MIME); videoPath=intent.getStringExtra(EXTRA_PATH);}
        else if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null){
            // Opened from another app (file manager, Telegram, browser…): Gallery X as the video player.
            uri=intent.getData(); mime=intent.getType(); name=externalName(uri);
            videoPath="file".equals(uri.getScheme())?uri.getPath():null;
            VideoSession.open(new ArrayList<>(),0);
        }
        else{finish();return;}
        displayMode=getSharedPreferences(PREFS_UI,MODE_PRIVATE).getInt("display_mode",0);
        if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(lp);}
        if(VaultStore.isVaultUri(uri))getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        audio=(AudioManager)getSystemService(Context.AUDIO_SERVICE); subtitleLanguage=SubtitlePrefs.language(this); buildUi(); setupGestures();
        video.setVideoURI(uri); video.setOnPreparedListener(this::prepared);
        video.setOnCompletionListener(p->{if(repeatVideo){video.seekTo(0);video.start();}else{clearPosition();showControls(true);play.setText("▶");}});
        video.setOnErrorListener((p,w,e)->{loading.setVisibility(View.GONE);Toast.makeText(this,"تعذر تشغيل هذا الفيديو",Toast.LENGTH_LONG).show();return true;});
        video.requestFocus(); handler.post(ticker); handler.post(subtitleTicker); immersive(); autoLoadSubtitle();
    }

    private void buildUi(){ theme=ThemePrefs.getStyle(this);
        root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        video=new VideoView(this); video.setLayerType(View.LAYER_TYPE_HARDWARE,null); root.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        loading=new ProgressBar(this); root.addView(loading,new FrameLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,54),Gravity.CENTER));
        gestureFeedback=action("",null,18);gestureFeedback.setPadding(Ui.dp(this,22),Ui.dp(this,12),Ui.dp(this,22),Ui.dp(this,12));gestureFeedback.setBackground(Ui.rounded(0xD9444444,16,this));gestureFeedback.setElevation(Ui.dp(this,10));gestureFeedback.setVisibility(View.GONE);root.addView(gestureFeedback,new FrameLayout.LayoutParams(-2,Ui.dp(this,54),Gravity.CENTER));
        top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),0); top.setBackgroundColor(0xB20A0710);
        top.addView(action("‹",v->finish(),42),new LinearLayout.LayoutParams(Ui.dp(this,58),Ui.dp(this,62)));
        title=action(name==null?"فيديو":name,null,17); title.setSingleLine(true); top.addView(title,new LinearLayout.LayoutParams(0,Ui.dp(this,62),1));
        speed=action("1×",v->chooseSpeed(),16); top.addView(speed,new LinearLayout.LayoutParams(Ui.dp(this,58),Ui.dp(this,52)));
        top.addView(action("▣",v->enterPip(),25),new LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,52)));
        top.addView(action("ⓘ",v->showInfo(),22),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,52)));
        root.addView(top,new FrameLayout.LayoutParams(-1,Ui.dp(this,62),Gravity.TOP));

        center=new LinearLayout(this); center.setGravity(Gravity.CENTER);
        center.addView(round("−10",v->seekBy(-10000),theme.surfaceAlt),box(72));
        play=round("▶",v->toggle(),theme.primary); LinearLayout.LayoutParams pp=box(82); pp.setMargins(Ui.dp(this,18),0,Ui.dp(this,18),0); center.addView(play,pp);
        center.addView(round("+10",v->seekBy(10000),theme.surfaceAlt),box(72)); root.addView(center,new FrameLayout.LayoutParams(-1,Ui.dp(this,96),Gravity.CENTER));

        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(Ui.dp(this,10),Ui.dp(this,4),Ui.dp(this,10),Ui.dp(this,6)); bottom.setBackgroundColor(0xDD100B1A);
        LinearLayout line=new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL); elapsed=action("00:00",null,13); duration=action("00:00",null,13); seek=new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.primary)); line.addView(elapsed,new LinearLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,42))); line.addView(seek,new LinearLayout.LayoutParams(0,Ui.dp(this,42),1)); line.addView(duration,new LinearLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,42))); bottom.addView(line,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(user)elapsed.setText(time(p));} public void onStartTrackingTouch(SeekBar b){dragging=true;keepVisible();} public void onStopTrackingTouch(SeekBar b){dragging=false;video.seekTo(b.getProgress());scheduleHide();}});
        LinearLayout actions=new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        actions.addView(action("↗\nمشاركة",v->share(),14),weight()); favorite=action("♡\nمفضلة",v->toggleFavorite(),14); actions.addView(favorite,weight());
        actions.addView(action("⟳\nتدوير",v->fullscreen(),14),weight()); actions.addView(action("🔒\nقفل",v->setLocked(true),14),weight()); actions.addView(action("⌫\nحذف",v->confirmDelete(),14),weight());
        bottom.addView(actions,new LinearLayout.LayoutParams(-1,Ui.dp(this,64)));
        HorizontalScrollView extraScroll=new HorizontalScrollView(this);extraScroll.setHorizontalScrollBarEnabled(false);LinearLayout extras=new LinearLayout(this);extras.setGravity(Gravity.CENTER);
        addExtra(extras,"⏮ السابق",this::previousVideo,theme.surfaceAlt);addExtra(extras,"التالي ⏭",this::nextVideo,theme.surfaceAlt);addExtra(extras,"▣ العرض",this::cycleDisplay,theme.surfaceAlt);addExtra(extras,"CC ترجمة",this::subtitleMenu,theme.surfaceAlt);addExtra(extras,"☰ السطور",this::openCueList,theme.surfaceAlt);addExtra(extras,"▧ لقطة",this::captureFrame,theme.surfaceAlt);addExtra(extras,"↻ تكرار",this::toggleRepeat,theme.surfaceAlt);addExtra(extras,"A–B مقطع",this::markAb,theme.surfaceAlt);extraScroll.addView(extras);bottom.addView(extraScroll,new LinearLayout.LayoutParams(-1,Ui.dp(this,58)));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,Ui.dp(this,164),Gravity.BOTTOM));
        subtitle=action("",null,18);subtitle.setPadding(Ui.dp(this,12),Ui.dp(this,5),Ui.dp(this,12),Ui.dp(this,5));subtitle.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);subtitle.setVisibility(View.GONE);applySubtitleStyle();FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);sp.bottomMargin=Ui.dp(this,176);sp.leftMargin=Ui.dp(this,20);sp.rightMargin=Ui.dp(this,20);root.addView(subtitle,sp);
        unlock=round("🔓 فتح القفل",v->setLocked(false),theme.primary); unlock.setVisibility(View.GONE); FrameLayout.LayoutParams up=new FrameLayout.LayoutParams(Ui.dp(this,150),Ui.dp(this,54),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); up.bottomMargin=Ui.dp(this,24); root.addView(unlock,up);
        buildCueList(); updateFavorite(); setContentView(root);
        root.setOnApplyWindowInsetsListener(this::onInsets);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->{if(r-l!=oright-ol||b-t!=ob-ot)video.post(this::applyDisplay);});
    }
    private LinearLayout.LayoutParams box(int n){return new LinearLayout.LayoutParams(Ui.dp(this,n),Ui.dp(this,n));}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-1,1);}
    private void addExtra(LinearLayout row,String text,Runnable run,int color){TextView v=round(text,x->run.run(),color);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Ui.dp(this,108),Ui.dp(this,48));p.setMargins(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));row.addView(v,p);}
    private TextView action(String text,View.OnClickListener click,float size){TextView v=new TextView(this);v.setText(text);v.setTextColor(Color.WHITE);v.setTextSize(Ui.textSize(this,size));v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);v.setGravity(Gravity.CENTER);if(click!=null)v.setOnClickListener(click);return v;}
    private TextView round(String text,View.OnClickListener click,int color){TextView v=action(text,click,16);v.setBackground(Ui.rounded(color,22,this));v.setElevation(Ui.dp(this,7));return v;}

    private void prepared(MediaPlayer p){player=p;player.setLooping(repeatVideo);loading.setVisibility(View.GONE);int saved=getSharedPreferences(PREFS,MODE_PRIVATE).getInt(uri.toString(),0);if(saved>3000&&saved<video.getDuration()-3000)video.seekTo(saved);playbackSpeed=VideoMemory.speed(this,uri.toString());speed.setText(speedLabel(playbackSpeed));applySpeed();autoOrient(p);video.post(this::applyDisplay);video.start();scheduleHide();}
    private void setupGestures(){gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
        @Override public boolean onDown(MotionEvent e){return true;}
        @Override public boolean onSingleTapConfirmed(MotionEvent e){if(!locked)showControls(!visible);return true;}
        @Override public boolean onDoubleTap(MotionEvent e){if(locked)return true;boolean f=e.getX()>=root.getWidth()/2f;seekBy(f?10000:-10000);Toast.makeText(VideoPlayerActivity.this,f?"+10 ثوانٍ":"−10 ثوانٍ",Toast.LENGTH_SHORT).show();return true;}
        @Override public boolean onScroll(MotionEvent first,MotionEvent now,float dx,float dy){if(locked||scaleGestures.isInProgress())return false;if(zoom>1.02f){video.setTranslationX(clamp(video.getTranslationX()-dx,-root.getWidth()/3f,root.getWidth()/3f));video.setTranslationY(clamp(video.getTranslationY()-dy,-root.getHeight()/3f,root.getHeight()/3f));return true;}if(Math.abs(dy)<Math.abs(dx))return false;float amount=dy/Math.max(1f,root.getHeight());if(first.getX()<root.getWidth()/2f)brightness(amount);else volume(amount);return true;}
    });scaleGestures=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScaleBegin(ScaleGestureDetector d){handler.removeCallbacks(autoHide);video.setPivotX(clamp(d.getFocusX()-video.getLeft(),0,video.getWidth()));video.setPivotY(clamp(d.getFocusY()-video.getTop(),0,video.getHeight()));return true;}@Override public boolean onScale(ScaleGestureDetector d){float raw=zoom*d.getScaleFactor();zoom=clamp(zoom+(raw-zoom)*0.55f,0.7f,4f);applyDisplay();showGestureValue(pinchLabel());return true;}@Override public void onScaleEnd(ScaleGestureDetector d){snapZoom();scheduleHide();}});View.OnTouchListener touch=(v,e)->{scaleGestures.onTouchEvent(e);boolean handled=gestures.onTouchEvent(e);return handled||scaleGestures.isInProgress();};root.setOnTouchListener(touch);video.setOnTouchListener(touch);}
    private void toggle(){if(video.isPlaying()){video.pause();play.setText("▶");keepVisible();}else{video.start();play.setText("Ⅱ");scheduleHide();}}
    private void seekBy(int delta){if(video.getDuration()<=0)return;int target=Math.max(0,Math.min(video.getDuration(),video.getCurrentPosition()+delta));video.seekTo(target);seek.setProgress(target);scheduleHide();}
    private void chooseSpeed(){String[] labels={"0.25×","0.5×","0.75×","1×","1.25×","1.5×","2×"};float[] values={.25f,.5f,.75f,1f,1.25f,1.5f,2f};new AlertDialog.Builder(this).setTitle("سرعة التشغيل").setItems(labels,(d,i)->{playbackSpeed=values[i];speed.setText(labels[i]);applySpeed();VideoMemory.setSpeed(this,uri.toString(),playbackSpeed);scheduleHide();}).show();}
    private void applySpeed(){if(player==null)return;try{player.setPlaybackParams(player.getPlaybackParams().setSpeed(playbackSpeed));}catch(Exception ignored){}}
    private void brightness(float delta){WindowManager.LayoutParams p=getWindow().getAttributes();float now=p.screenBrightness<0?.5f:p.screenBrightness;p.screenBrightness=Math.max(.05f,Math.min(1f,now+delta));getWindow().setAttributes(p);showGestureValue("☀  السطوع "+Math.round(p.screenBrightness*100)+"%");}
    private void volume(float delta){int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),now=audio.getStreamVolume(AudioManager.STREAM_MUSIC);int next=Math.max(0,Math.min(max,now+Math.round(delta*max)));audio.setStreamVolume(AudioManager.STREAM_MUSIC,next,0);showGestureValue((next==0?"🔇":"♪")+"  الصوت "+Math.round(next*100f/Math.max(1,max))+"%");}
    private void showGestureValue(String text){handler.removeCallbacks(hideGestureFeedback);gestureFeedback.animate().cancel();gestureFeedback.setText(text);gestureFeedback.setAlpha(1f);gestureFeedback.setVisibility(View.VISIBLE);gestureFeedback.bringToFront();handler.postDelayed(hideGestureFeedback,420);}
    private float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    private void cycleDisplay(){setDisplayMode((displayMode+1)%3);applyDisplay();String label=displayMode==0?"ملاءمة":displayMode==1?"ملء الشاشة":"تمديد";Toast.makeText(this,"طريقة العرض: "+label,Toast.LENGTH_SHORT).show();}
    private void applyDisplay(){if(video.getWidth()<=0||video.getHeight()<=0||root.getWidth()<=0||root.getHeight()<=0)return;float sx=1f,sy=1f;if(displayMode==1){float fill=Math.max((float)root.getWidth()/video.getWidth(),(float)root.getHeight()/video.getHeight());sx=sy=fill;}else if(displayMode==2){sx=(float)root.getWidth()/video.getWidth();sy=(float)root.getHeight()/video.getHeight();}video.setScaleX(sx*zoom);video.setScaleY(sy*zoom);}
    private void previousVideo(){MediaItem item=VideoSession.previous();if(item==null){Toast.makeText(this,"هذا أول فيديو",Toast.LENGTH_SHORT).show();return;}playItem(item);}
    private void nextVideo(){MediaItem item=VideoSession.next();if(item==null){Toast.makeText(this,"هذا آخر فيديو",Toast.LENGTH_SHORT).show();return;}playItem(item);}
    private void playItem(MediaItem item){savePosition();uri=item.uri;name=item.name;mime=item.mimeType;videoPath=item.filePath;title.setText(name==null?"فيديو":name);resetSubtitles();abStart=abEnd=-1;zoom=1f;video.setTranslationX(0);video.setTranslationY(0);loading.setVisibility(View.VISIBLE);video.setVideoURI(uri);video.start();autoLoadSubtitle();}
    private void toggleRepeat(){repeatVideo=!repeatVideo;if(player!=null)player.setLooping(repeatVideo);Toast.makeText(this,repeatVideo?"تكرار الفيديو: مفعّل":"تكرار الفيديو: متوقف",Toast.LENGTH_SHORT).show();}
    private void markAb(){long now=video.getCurrentPosition();if(abStart<0){abStart=now;abEnd=-1;Toast.makeText(this,"تم تحديد A عند "+time((int)now)+" — اضغط مرة ثانية لتحديد B",Toast.LENGTH_LONG).show();}else if(abEnd<0){if(now<=abStart+1000){Toast.makeText(this,"يجب أن تكون B بعد A بثانية على الأقل",Toast.LENGTH_SHORT).show();return;}abEnd=now;Toast.makeText(this,"تكرار المقطع "+time((int)abStart)+" إلى "+time((int)abEnd),Toast.LENGTH_LONG).show();}else{abStart=abEnd=-1;Toast.makeText(this,"تم إلغاء تكرار المقطع",Toast.LENGTH_SHORT).show();}}
    // ================================================================== subtitles

    private void subtitleMenu(){
        String[] items={"تحميل ملف ترجمة (SRT / VTT / ASS)","لغة الترجمة: "+SubtitlePrefs.languageLabel(subtitleLanguage),"قائمة سطور الترجمة","شكل الترجمة",subtitlesHidden?"إظهار الترجمة":"إخفاء الترجمة","مجلدات الترجمة التلقائية"+(subtitleFolderCount()>0?" ("+subtitleFolderCount()+")":"")};
        new AlertDialog.Builder(this).setTitle("الترجمة").setItems(items,(d,which)->{
            if(which==0)pickSubtitle(); else if(which==1)chooseLanguage(); else if(which==2)openCueList(); else if(which==3)styleMenu();
            else if(which==4){subtitlesHidden=!subtitlesHidden;updateSubtitleNow();}
            else subtitleFolders();
        }).show();
    }

    private void pickSubtitle(){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/x-subrip","text/vtt","text/x-ssa","text/x-ass","text/plain","application/octet-stream"});pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(pick,REQUEST_SUBTITLE);}

    private void loadSubtitle(Uri selected){loadSubtitle(selected,true,false);}

    /** remember: link the file to this video for next time. quiet: an automatic load, no error toast. */
    private void loadSubtitle(Uri selected,boolean remember,boolean quiet){
        Uri forVideo=uri;
        executor.execute(()->{
            List<SubtitleParser.Cue> result=readSubtitle(selected);
            runOnUiThread(()->{
                if(isFinishing()||!forVideo.equals(uri))return;
                if(result.isEmpty()){if(!quiet)Toast.makeText(this,"تعذر قراءة ملف الترجمة",Toast.LENGTH_LONG).show();return;}
                if(remember)VideoMemory.setSubtitle(this,uri.toString(),selected.toString());
                applySubtitles(result,quiet?"انفتحت الترجمة تلقائياً":"تم تحميل الترجمة: "+result.size()+" سطر");
            });
        });
    }

    private void applySubtitles(List<SubtitleParser.Cue> result,String message){
        if(translator!=null){translator.close();translator=null;}
        subtitles=result; subtitlesHidden=false; shownCue=-2; setListStatus("");
        if(cueAdapter!=null)cueAdapter.notifyDataSetChanged();
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
        restartTranslation();
    }

    private List<SubtitleParser.Cue> readSubtitle(Uri source){
        try(InputStream in=getContentResolver().openInputStream(source)){return readSubtitle(in);}catch(Exception error){return new ArrayList<>();}
    }

    private List<SubtitleParser.Cue> readSubtitle(InputStream in)throws IOException{
        if(in==null)throw new IOException("no stream");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[16384]; int read;
        while((read=in.read(buffer))>0){bytes.write(buffer,0,read); if(bytes.size()>8*1024*1024)throw new IOException("too large");}
        return SubtitleParser.parse(bytes.toByteArray());
    }

    /**
     * Finds a subtitle for the current video without asking: the one opened for it last time,
     * then a file with the same name next to the video, then the subtitle folders the user allowed.
     */
    private void autoLoadSubtitle(){
        if(uri==null)return;
        Uri forVideo=uri; String videoName=name; String path=videoPath; String language=subtitleLanguage;
        String remembered=VideoMemory.subtitle(this,uri.toString());
        executor.execute(()->{
            List<SubtitleParser.Cue> found=null;
            if(remembered!=null){found=readSubtitle(Uri.parse(remembered)); if(found.isEmpty())found=null;}
            String base=videoName!=null?videoName:path!=null?new File(path).getName():null;
            if(found==null&&path!=null&&base!=null)found=fromFolder(new File(path).getParentFile(),base,language);
            if(found==null&&base!=null)found=fromAllowedFolders(base,language);
            List<SubtitleParser.Cue> result=found;
            if(result==null||result.isEmpty())return;
            runOnUiThread(()->{
                if(isFinishing()||!forVideo.equals(uri)||!subtitles.isEmpty())return;
                applySubtitles(result,"انفتحت الترجمة تلقائياً");
            });
        });
    }

    private List<SubtitleParser.Cue> fromFolder(File folder,String videoName,String language){
        if(folder==null)return null;
        File[] files; try{files=folder.listFiles();}catch(Exception denied){return null;}
        if(files==null)return null;
        File best=null; int bestScore=-1;
        for(File file:files){int score=SubtitleMatch.score(videoName,file.getName(),language);if(score>bestScore){best=file;bestScore=score;}}
        if(best==null)return null;
        try(InputStream in=new FileInputStream(best)){List<SubtitleParser.Cue> cues=readSubtitle(in);return cues.isEmpty()?null:cues;}catch(Exception denied){return null;}
    }

    /** Searches the folders granted through "subtitle folders" (up to 3 levels deep). */
    private List<SubtitleParser.Cue> fromAllowedFolders(String videoName,String language){
        Uri bestUri=null; int bestScore=-1;
        for(UriPermission permission:getContentResolver().getPersistedUriPermissions()){
            Uri tree=permission.getUri();
            if(!permission.isReadPermission()||!android.provider.DocumentsContract.isTreeUri(tree))continue;
            ArrayDeque<String[]> queue=new ArrayDeque<>(); queue.add(new String[]{android.provider.DocumentsContract.getTreeDocumentId(tree),"0"});
            int visited=0;
            while(!queue.isEmpty()&&visited<4000&&bestScore<4){
                String[] next=queue.poll(); int depth=Integer.parseInt(next[1]);
                Uri children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,next[0]);
                try(android.database.Cursor c=getContentResolver().query(children,new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
                    while(c!=null&&c.moveToNext()){
                        visited++;
                        String id=c.getString(0),file=c.getString(1),type=c.getString(2);
                        if(android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(type)){if(depth<3)queue.add(new String[]{id,String.valueOf(depth+1)});continue;}
                        int score=SubtitleMatch.score(videoName,file,language);
                        if(score>bestScore){bestScore=score;bestUri=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,id);}
                    }
                }catch(Exception ignored){}
            }
        }
        if(bestUri==null)return null;
        List<SubtitleParser.Cue> cues=readSubtitle(bestUri);
        return cues.isEmpty()?null:cues;
    }

    private int subtitleFolderCount(){
        int count=0;
        for(UriPermission permission:getContentResolver().getPersistedUriPermissions())
            if(permission.isReadPermission()&&android.provider.DocumentsContract.isTreeUri(permission.getUri()))count++;
        return count;
    }

    private void subtitleFolders(){
        int count=subtitleFolderCount();
        if(count==0){pickSubtitleFolder();return;}
        new AlertDialog.Builder(this).setTitle("مجلدات الترجمة التلقائية ("+count+")")
                .setMessage("أي ملف ترجمة بنفس اسم الفيديو داخل هذي المجلدات ينفتح وحده.")
                .setPositiveButton("إضافة مجلد",(d,w)->pickSubtitleFolder())
                .setNegativeButton("إزالة المجلدات",(d,w)->{
                    for(UriPermission permission:getContentResolver().getPersistedUriPermissions())
                        if(android.provider.DocumentsContract.isTreeUri(permission.getUri()))
                            try{getContentResolver().releasePersistableUriPermission(permission.getUri(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
                    Toast.makeText(this,"انشالت المجلدات",Toast.LENGTH_SHORT).show();
                }).setNeutralButton("إلغاء",null).show();
    }

    private void pickSubtitleFolder(){
        Intent tree=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        tree.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Toast.makeText(this,"اختار المجلد اللي تحط بيه ملفات الترجمة (مثل Download)",Toast.LENGTH_LONG).show();
        try{startActivityForResult(tree,REQUEST_TREE);}catch(Exception error){Toast.makeText(this,"مدير الملفات ما يدعم اختيار مجلد",Toast.LENGTH_LONG).show();}
    }

    private void resetSubtitles(){
        if(translator!=null){translator.close();translator=null;}
        subtitles=new ArrayList<>(); shownCue=-2; setListStatus("");
        if(cueAdapter!=null)cueAdapter.notifyDataSetChanged();
        if(listPanel!=null)listPanel.setVisibility(View.GONE);
        subtitle.setText(""); subtitle.setVisibility(View.GONE);
    }

    private void chooseLanguage(){
        String[] values={SubtitlePrefs.ORIGINAL,SubtitlePrefs.ARABIC,SubtitlePrefs.ENGLISH};
        String[] labels={"اللغة الأصلية (بدون ترجمة)","العربية","English"};
        int checked=Math.max(0,Arrays.asList(values).indexOf(subtitleLanguage));
        new AlertDialog.Builder(this).setTitle("ترجم الترجمة إلى").setSingleChoiceItems(labels,checked,(d,which)->{
            d.dismiss();
            if(values[which].equals(subtitleLanguage))return;
            subtitleLanguage=values[which]; SubtitlePrefs.setLanguage(this,subtitleLanguage);
            restartTranslation();
        }).show();
    }

    private boolean translating(){return !SubtitlePrefs.ORIGINAL.equals(subtitleLanguage);}

    private void restartTranslation(){
        if(translator!=null){translator.close();translator=null;}
        for(SubtitleParser.Cue cue:subtitles)cue.translated=null;
        setListStatus(""); updateSubtitleNow();
        if(!translating())return;
        if(subtitles.isEmpty()){Toast.makeText(this,"حمّل ملف ترجمة أولاً حتى أترجمه",Toast.LENGTH_LONG).show();return;}
        List<SubtitleParser.Cue> list=subtitles; String target=subtitleLanguage;
        SubtitleTranslator.detectLanguage(list,source->{
            if(stale(list,target))return;
            if(source.equals(target)){Toast.makeText(this,"الترجمة أصلاً بـ"+SubtitlePrefs.languageLabel(target)+"، تنعرض مثل ما هي",Toast.LENGTH_LONG).show();return;}
            SubtitleTranslator.isPackReady(source,sourceReady->SubtitleTranslator.isPackReady(target,targetReady->{
                if(stale(list,target))return;
                if(sourceReady&&targetReady){beginTranslation(list,source,target);return;}
                new AlertDialog.Builder(this).setTitle("تنزيل حزمة اللغة")
                        .setMessage("أول مرة لازم تنزّل حزمة الترجمة (حوالي 30 ميغا). تنزل مرة وحدة بس، وبعدها الترجمة تشتغل بدون إنترنت.")
                        .setNegativeButton("إلغاء",null)
                        .setPositiveButton("تنزيل",(d,w)->{if(!stale(list,target))beginTranslation(list,source,target);}).show();
            }));
        });
    }

    private boolean stale(List<SubtitleParser.Cue> list,String target){return isFinishing()||list!=subtitles||!target.equals(subtitleLanguage);}

    private void beginTranslation(List<SubtitleParser.Cue> list,String source,String target){
        if(translator!=null)translator.close();
        setListStatus("جارِ تجهيز الترجمة…");
        Toast.makeText(this,"جارِ الترجمة إلى "+SubtitlePrefs.languageLabel(target)+"…",Toast.LENGTH_SHORT).show();
        translator=new SubtitleTranslator(list,new SubtitleTranslator.Listener(){
            @Override public void onCueTranslated(int index){
                if(list!=subtitles)return;
                if(index==shownCue||SubtitleParser.active(subtitles,video.getCurrentPosition()).contains(index))updateSubtitleNow();
                if(listPanel.getVisibility()==View.VISIBLE&&index>=cueList.getFirstVisiblePosition()&&index<=cueList.getLastVisiblePosition())cueAdapter.notifyDataSetChanged();
            }
            @Override public void onProgress(int done,int total){
                if(list!=subtitles)return;
                setListStatus(done>=total?"اكتملت الترجمة ✓":"مترجم "+done+" من "+total);
            }
            @Override public void onFailed(String message){
                if(list!=subtitles)return;
                setListStatus("");
                Toast.makeText(VideoPlayerActivity.this,message,Toast.LENGTH_LONG).show();
                if(translator!=null){translator.close();translator=null;}
            }
        });
        translator.focus(Math.max(0,SubtitleParser.lastStarted(list,video.getCurrentPosition())));
        translator.start(source,target);
    }

    private void updateSubtitleNow(){if(video!=null)updateSubtitle(video.getCurrentPosition());}

    private void updateSubtitle(long position){
        List<Integer> active=SubtitleParser.active(subtitles,position);
        StringBuilder text=new StringBuilder();
        if(!subtitlesHidden)for(int index:active){
            SubtitleParser.Cue cue=subtitles.get(index);
            String line=translating()&&cue.translated!=null?cue.translated:cue.text;
            if(text.length()>0)text.append('\n');
            text.append(line);
        }
        String value=text.toString();
        if(!value.contentEquals(subtitle.getText()))subtitle.setText(value);
        subtitle.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
        int current=active.isEmpty()?SubtitleParser.lastStarted(subtitles,position):active.get(active.size()-1);
        if(translator!=null)translator.focus(Math.max(0,current));
        if(current!=shownCue){
            shownCue=current;
            if(listPanel!=null&&listPanel.getVisibility()==View.VISIBLE){
                cueAdapter.notifyDataSetChanged();
                if(current>=0&&SystemClock.uptimeMillis()-listTouchedAt>3000)cueList.smoothScrollToPositionFromTop(current,Ui.dp(this,90),220);
            }
        }
    }

    // ------------------------------------------------------------------ subtitle look

    private void applySubtitleStyle(){
        if(subtitle==null)return;
        subtitle.setTextSize(Ui.textSize(this,SubtitlePrefs.SIZES[SubtitlePrefs.size(this)]));
        int color=SubtitlePrefs.color(this);
        subtitle.setTextColor(color==0?Color.WHITE:color==1?0xFFFFE45C:neon(theme.primary));
        int background=SubtitlePrefs.background(this);
        subtitle.setBackground(background==0?null:Ui.rounded(background==1?0x8C000000:0xE6000000,10,this));
        if(SubtitlePrefs.glow(this))subtitle.setShadowLayer(Ui.dp(this,7),0,0,theme.primary);
        else if(background==0)subtitle.setShadowLayer(Ui.dp(this,3),0,Ui.dp(this,1),Color.BLACK);
        else subtitle.setShadowLayer(0,0,0,Color.TRANSPARENT);
        subtitle.setLineSpacing(Ui.dp(this,2),1f);
    }

    /** A lighter tint of the theme color so neon text stays readable on dark video. */
    private static int neon(int color){
        int r=Color.red(color),g=Color.green(color),b=Color.blue(color);
        return Color.rgb(r+(255-r)*45/100,g+(255-g)*45/100,b+(255-b)*45/100);
    }

    private void styleMenu(){
        String[] items={"الحجم: "+SubtitlePrefs.SIZE_LABELS[SubtitlePrefs.size(this)],"اللون: "+SubtitlePrefs.COLOR_LABELS[SubtitlePrefs.color(this)],"الخلفية: "+SubtitlePrefs.BACKGROUND_LABELS[SubtitlePrefs.background(this)],"توهج نيون: "+(SubtitlePrefs.glow(this)?"مفعّل":"متوقف")};
        new AlertDialog.Builder(this).setTitle("شكل الترجمة").setItems(items,(d,which)->{
            if(which==0)choose("حجم الخط",SubtitlePrefs.SIZE_LABELS,SubtitlePrefs.size(this),v->SubtitlePrefs.setSize(this,v));
            else if(which==1)choose("لون الخط",SubtitlePrefs.COLOR_LABELS,SubtitlePrefs.color(this),v->SubtitlePrefs.setColor(this,v));
            else if(which==2)choose("الخلفية",SubtitlePrefs.BACKGROUND_LABELS,SubtitlePrefs.background(this),v->SubtitlePrefs.setBackground(this,v));
            else{SubtitlePrefs.setGlow(this,!SubtitlePrefs.glow(this));applySubtitleStyle();previewSubtitle();styleMenu();}
        }).setPositiveButton("تم",null).show();
    }

    private interface IntChoice{void set(int value);}

    private void choose(String title,String[] labels,int checked,IntChoice choice){
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,checked,(d,which)->{
            d.dismiss(); choice.set(which); applySubtitleStyle(); previewSubtitle(); styleMenu();
        }).show();
    }

    private final Runnable endPreview=this::updateSubtitleNow;

    private void previewSubtitle(){
        if(subtitle.getVisibility()==View.VISIBLE&&subtitle.getText().length()>0)return;
        subtitle.setText("هكذا تظهر الترجمة\nThis is how subtitles look");
        subtitle.setVisibility(View.VISIBLE);
        handler.removeCallbacks(endPreview);
        handler.postDelayed(endPreview,2500);
    }

    // ------------------------------------------------------------------ cue list panel

    private void buildCueList(){
        listPanel=new LinearLayout(this); listPanel.setOrientation(LinearLayout.VERTICAL);
        listPanel.setBackground(Ui.rounded(0xF20A0710,18,this)); listPanel.setElevation(Ui.dp(this,14));
        listPanel.setPadding(Ui.dp(this,8),Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,8)); listPanel.setVisibility(View.GONE);
        listPanel.setClickable(true);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=action("سطور الترجمة",null,16); heading.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        header.addView(heading,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));
        listStatus=action("",null,12); listStatus.setTextColor(neon(theme.primary)); listStatus.setTypeface(Typeface.DEFAULT);
        header.addView(listStatus,new LinearLayout.LayoutParams(-2,Ui.dp(this,44)));
        TextView close=action("✕",v->listPanel.setVisibility(View.GONE),20); close.setContentDescription("إغلاق");
        header.addView(close,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,44)));
        listPanel.addView(header,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));
        cueList=new ListView(this); cueList.setDivider(null); cueList.setDividerHeight(Ui.dp(this,4)); cueList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        cueAdapter=new CueAdapter(); cueList.setAdapter(cueAdapter);
        cueList.setOnItemClickListener((parent,view,position,id)->{
            if(position>=subtitles.size())return;
            int target=(int)subtitles.get(position).start;
            video.seekTo(target); seek.setProgress(target); elapsed.setText(time(target)); updateSubtitle(target);
        });
        cueList.setOnTouchListener((v,e)->{listTouchedAt=SystemClock.uptimeMillis();return false;});
        listPanel.addView(cueList,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(listPanel,new FrameLayout.LayoutParams(-1,-1));
    }

    private void openCueList(){
        if(subtitles.isEmpty()){Toast.makeText(this,"حمّل ملف ترجمة أولاً",Toast.LENGTH_SHORT).show();pickSubtitle();return;}
        layoutCueList(); listPanel.setVisibility(View.VISIBLE); listPanel.bringToFront();
        cueAdapter.notifyDataSetChanged(); listTouchedAt=0;
        int current=SubtitleParser.lastStarted(subtitles,video.getCurrentPosition());
        if(current>=0)cueList.setSelectionFromTop(current,Ui.dp(this,90));
    }

    private void layoutCueList(){
        boolean land=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int margin=Ui.dp(this,10);
        FrameLayout.LayoutParams params=land
                ?new FrameLayout.LayoutParams(Math.max(Ui.dp(this,300),root.getWidth()*42/100),-1,Gravity.END)
                :new FrameLayout.LayoutParams(-1,Math.max(Ui.dp(this,280),root.getHeight()*52/100),Gravity.BOTTOM);
        params.setMargins(margin,land?margin:0,margin,margin);
        listPanel.setLayoutParams(params);
    }

    private void setListStatus(String text){if(listStatus!=null)listStatus.setText(text);}
    private void captureFrame(){int position=video.getCurrentPosition();loading.setVisibility(View.VISIBLE);executor.execute(()->{Bitmap frame=null;MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(this,uri);frame=retriever.getFrameAtTime(position*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);}catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}Bitmap ready=frame;Uri saved=ready==null?null:saveCapture(ready);runOnUiThread(()->{loading.setVisibility(View.GONE);Toast.makeText(this,saved==null?"تعذر التقاط الصورة":"حُفظت اللقطة في Pictures/Gallery X Captures",Toast.LENGTH_LONG).show();});});}
    private Uri saveCapture(Bitmap bitmap){Uri out=null;try{ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"GalleryX_"+System.currentTimeMillis()+".jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Gallery X Captures");values.put(MediaStore.Images.Media.IS_PENDING,1);}out=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(out==null)return null;try(OutputStream stream=getContentResolver().openOutputStream(out)){if(stream==null||!bitmap.compress(Bitmap.CompressFormat.JPEG,95,stream))throw new IOException();}if(Build.VERSION.SDK_INT>=29){ContentValues done=new ContentValues();done.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(out,done,null,null);}return out;}catch(Exception e){if(out!=null)try{getContentResolver().delete(out,null,null);}catch(Exception ignored){}return null;}}
    private void showInfo(){loading.setVisibility(View.VISIBLE);executor.execute(()->{String details=readInfo();runOnUiThread(()->{loading.setVisibility(View.GONE);new AlertDialog.Builder(this).setTitle("معلومات الفيديو").setMessage(details).setPositiveButton("حسناً",null).show();});});}
    private String readInfo(){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(this,uri);String w=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),h=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),rotation=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),bitrate=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE),codec=mime==null?"غير معروف":mime;long size=-1;try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst())size=c.getLong(0);}String quality=(w!=null&&h!=null)?w+" × "+h:"غير معروفة";return "الاسم: "+(name==null?"فيديو":name)+"\nالدقة: "+quality+"\nالصيغة: "+codec+"\nالدوران: "+(rotation==null?"0":rotation)+"°\nمعدل البت: "+(bitrate==null?"غير معروف":(Long.parseLong(bitrate)/1000)+" kbps")+"\nالحجم: "+(size<0?"غير معروف":String.format(Locale.US,"%.2f MB",size/1048576f))+"\n\nيدعم المشغّل أعلى دقة يستطيع الجهاز فك ترميزها، بما فيها 4K/8K على الأجهزة المتوافقة.";}catch(Exception e){return "تعذر قراءة تفاصيل هذا الفيديو";}finally{try{r.release();}catch(Exception ignored){}}}
    private void showControls(boolean show){visible=show;int s=show?View.VISIBLE:View.GONE;top.setVisibility(s);center.setVisibility(s);bottom.setVisibility(s);if(show)scheduleHide();}
    private void keepVisible(){handler.removeCallbacks(autoHide);showControls(true);}
    private void scheduleHide(){handler.removeCallbacks(autoHide);handler.postDelayed(autoHide,3200);}
    private void setLocked(boolean value){locked=value;handler.removeCallbacks(autoHide);top.setVisibility(value?View.GONE:View.VISIBLE);center.setVisibility(value?View.GONE:View.VISIBLE);bottom.setVisibility(value?View.GONE:View.VISIBLE);unlock.setVisibility(value?View.VISIBLE:View.GONE);visible=!value;if(!value)scheduleHide();}
    private void fullscreen(){userRotated=true;boolean land=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;setRequestedOrientation(land?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);immersive();}

    // ================================================================== full screen

    /** Hides status and navigation bars; a swipe from the edge shows them for a moment. */
    private void immersive(){
        View decor=getWindow().getDecorView();
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null){controller.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}
        }else{
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)immersive();}

    /** The picture uses the whole screen, camera cut-out included; only the buttons keep clear of it. */
    private WindowInsets onInsets(View view,WindowInsets insets){
        int left=0,topInset=0,right=0,bottomInset=0;
        if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){
            android.view.DisplayCutout cutout=insets.getDisplayCutout();
            left=cutout.getSafeInsetLeft();topInset=cutout.getSafeInsetTop();right=cutout.getSafeInsetRight();bottomInset=cutout.getSafeInsetBottom();
        }
        keepClear(top,left,topInset,right,0); keepClear(bottom,left,0,right,bottomInset);
        return insets;
    }

    private void keepClear(View view,int left,int topMargin,int right,int bottomMargin){
        if(view==null||!(view.getLayoutParams() instanceof FrameLayout.LayoutParams))return;
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)view.getLayoutParams();
        params.leftMargin=left;params.topMargin=topMargin;params.rightMargin=right;params.bottomMargin=bottomMargin;
        view.setLayoutParams(params);
    }

    /** Landscape videos open in landscape, portrait ones in portrait (until the user rotates by hand). */
    private void autoOrient(MediaPlayer p){
        if(userRotated||isInPictureInPictureMode())return;
        int w=p.getVideoWidth(),h=p.getVideoHeight();
        if(w<=0||h<=0)return;
        setRequestedOrientation(w>=h?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    /** How much the video must grow, from "fit", to cover the whole screen. */
    private float fillFactor(){
        if(video.getWidth()<=0||video.getHeight()<=0||root.getWidth()<=0)return 1f;
        return Math.max(1f,Math.max((float)root.getWidth()/video.getWidth(),(float)root.getHeight()/video.getHeight()));
    }

    private String pinchLabel(){
        if(displayMode==0&&zoom>1.04f&&zoom<=fillFactor()*1.2f)return "اترك إصبعك: ملء الشاشة";
        if(displayMode!=0&&zoom<0.96f)return "اترك إصبعك: ملاءمة";
        return zoom<1f?"ملاءمة":"التكبير "+Math.round(zoom*100)+"%";
    }

    /** Pinch out snaps to full screen (no black bars), pinch in snaps back to fit. */
    private void snapZoom(){
        if(displayMode==0&&zoom>1.04f&&zoom<=fillFactor()*1.2f){setDisplayMode(1);showGestureValue("ملء الشاشة");}
        else if(displayMode!=0&&zoom<0.96f){setDisplayMode(0);showGestureValue("ملاءمة");}
        else if(zoom<=1.02f){zoom=1f;video.setTranslationX(0);video.setTranslationY(0);}
        applyDisplay();
    }

    private void setDisplayMode(int mode){
        displayMode=mode;zoom=1f;video.setTranslationX(0);video.setTranslationY(0);
        getSharedPreferences(PREFS_UI,MODE_PRIVATE).edit().putInt("display_mode",mode).apply();
    }

    private static String speedLabel(float value){
        String text=String.valueOf(value);
        if(text.endsWith(".0"))text=text.substring(0,text.length()-2);
        return text+"×";
    }

    private String externalName(Uri source){
        try(android.database.Cursor c=getContentResolver().query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)return c.getString(0);
        }catch(Exception ignored){}
        String last=source.getLastPathSegment();
        return last==null?"فيديو":last.substring(last.lastIndexOf('/')+1);
    }
    private void enterPip(){if(Build.VERSION.SDK_INT<26||isInPictureInPictureMode())return;try{int w=player==null?16:Math.max(1,player.getVideoWidth()),h=player==null?9:Math.max(1,player.getVideoHeight());enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(w,h)).build());}catch(Exception e){Toast.makeText(this,"النافذة المصغرة غير مدعومة على هذا الجهاز",Toast.LENGTH_SHORT).show();}}
    @Override public void onPictureInPictureModeChanged(boolean pip,android.content.res.Configuration c){super.onPictureInPictureModeChanged(pip,c);if(pip){top.setVisibility(View.GONE);center.setVisibility(View.GONE);bottom.setVisibility(View.GONE);unlock.setVisibility(View.GONE);}else if(!locked)showControls(true);}
    private String time(int ms){int t=Math.max(0,ms/1000),h=t/3600,m=t%3600/60,s=t%60;return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,s):String.format(Locale.US,"%02d:%02d",m,s);}
    private void share(){ShareHelper.share(this,Collections.singletonList(new MediaItem(0,uri,name,"","",0,MediaTypes.isVideo(mime)?mime:"video/*")),"مشاركة الفيديو");}
    private void toggleFavorite(){FavoritesStore.toggle(this,uri.toString());updateFavorite();}
    private void updateFavorite(){if(favorite==null)return;boolean saved=FavoritesStore.contains(this,uri.toString());favorite.setText((saved?"♥":"♡")+"\n"+(saved?"إزالة":"مفضلة"));favorite.setTextColor(saved?0xFFFF7AD9:Color.WHITE);}
    private void confirmDelete(){new AlertDialog.Builder(this).setTitle(VaultStore.isVaultUri(uri)?"حذف نهائي":"حذف الفيديو").setMessage(VaultStore.isVaultUri(uri)?"سيتم حذف الفيديو نهائياً من الخزنة.":"نقل الفيديو إلى سلة المحذوفات؟").setNegativeButton("إلغاء",null).setPositiveButton("حذف",(d,w)->delete()).show();}
    private void delete(){if(VaultStore.isVaultUri(uri)){if(VaultStore.delete(this,uri))finishDeleted();return;}try{if(Build.VERSION.SDK_INT>=30){PendingIntent p=MediaStore.createTrashRequest(getContentResolver(),Collections.singletonList(uri),true);startIntentSenderForResult(p.getIntentSender(),REQUEST_TRASH,null,0,0,0);}else if(getContentResolver().delete(uri,null,null)>0)finishDeleted();}catch(Exception e){Toast.makeText(this,"تعذر حذف الفيديو",Toast.LENGTH_SHORT).show();}}
    @Override protected void onActivityResult(int r,int result,Intent data){super.onActivityResult(r,result,data);if(r==REQUEST_TRASH&&result==RESULT_OK)finishDeleted();else if(r==REQUEST_SUBTITLE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            loadSubtitle(data.getData());
        }else if(r==REQUEST_TREE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Toast.makeText(this,"تمام. أي ترجمة بنفس اسم الفيديو بهذا المجلد راح تنفتح وحدها",Toast.LENGTH_LONG).show();
                if(subtitles.isEmpty())autoLoadSubtitle();
            }catch(Exception error){Toast.makeText(this,"ما گدرت أحفظ صلاحية المجلد",Toast.LENGTH_LONG).show();}
        }}
    private void finishDeleted(){clearPosition();setResult(RESULT_OK);finish();}
    private void savePosition(){if(video==null||video.getDuration()<=0)return;int p=video.getCurrentPosition();if(p>3000&&p<video.getDuration()-3000)getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(uri.toString(),p).apply();else clearPosition();}
    private void clearPosition(){getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(uri.toString()).apply();}
    @Override protected void onPause(){super.onPause();savePosition();if(Build.VERSION.SDK_INT<26||!isInPictureInPictureMode())if(video!=null&&video.isPlaying())video.pause();}
    @Override protected void onResume(){super.onResume();if(VaultStore.isVaultUri(uri)&&!VaultSession.isUnlocked())finish();}
    @Override public void onBackPressed(){if(listPanel!=null&&listPanel.getVisibility()==View.VISIBLE){listPanel.setVisibility(View.GONE);return;}super.onBackPressed();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(listPanel!=null&&listPanel.getVisibility()==View.VISIBLE)root.post(this::layoutCueList);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(translator!=null)translator.close();executor.shutdownNow();super.onDestroy();}
    private final class CueAdapter extends BaseAdapter{
        @Override public int getCount(){return subtitles.size();}
        @Override public Object getItem(int position){return subtitles.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View convert,ViewGroup parent){
            LinearLayout row; TextView when,original,translated;
            if(convert instanceof LinearLayout){row=(LinearLayout)convert;when=(TextView)row.getChildAt(0);original=(TextView)row.getChildAt(1);translated=(TextView)row.getChildAt(2);}
            else{
                row=new LinearLayout(VideoPlayerActivity.this); row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(VideoPlayerActivity.this,12),Ui.dp(VideoPlayerActivity.this,8),Ui.dp(VideoPlayerActivity.this,12),Ui.dp(VideoPlayerActivity.this,8));
                when=line(12,0xFFA0A0B8); original=line(15,Color.WHITE); translated=line(15,neon(theme.primary));
                row.addView(when); row.addView(original); row.addView(translated);
            }
            SubtitleParser.Cue cue=subtitles.get(position);
            when.setText(time((int)cue.start));
            original.setText(cue.text);
            boolean showTranslation=translating()&&cue.translated!=null&&!cue.translated.equals(cue.text);
            translated.setText(showTranslation?cue.translated:"");
            translated.setVisibility(showTranslation?View.VISIBLE:View.GONE);
            boolean current=position==shownCue;
            row.setBackground(current?Ui.rounded(Ui.withAlpha(theme.primary,70),12,VideoPlayerActivity.this):null);
            row.setAlpha(current?1f:0.82f);
            return row;
        }
        private TextView line(float size,int color){
            TextView view=new TextView(VideoPlayerActivity.this);
            view.setTextSize(Ui.textSize(VideoPlayerActivity.this,size)); view.setTextColor(color);
            view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR); view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            return view;
        }
    }
}
