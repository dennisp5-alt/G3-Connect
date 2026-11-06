package com.dennis.stemstudio;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Six stems from one HT-Demucs model. Leaves the two, three and four-stem screens alone. */
public final class SixStemActivity extends Activity {
    private static final int PICK_MODEL=101, PICK_WAV=103,
       PICK_ZIP=104, PICK_FOLDER=105;
    private static final int BG=Color.rgb(14,18,24), PANEL=Color.rgb(27,34,43),
       FG=Color.rgb(242,247,250), MUTE=Color.rgb(153,169,181),
       AQUA=Color.rgb(97,207,212);
    private static final String[] LABELS={"Vocals","Drums","Bass","Other","Guitar","Piano"};
    private static final int[] START={0,60,0,0,30,60,90,120,180};
    private static final int[] DURATIONS={0,60,60,10,10,10,10,10,10};
    private static final int[] THREADS={4,2,5,6};
    private static final String PREFERENCES="six_stem_diagnostics";
    private static final String MODEL_FILENAME="htdemucs_6s.onnx";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private File model,wav;
    private String[] stems;
    private TextView modelLabel,wavLabel,status;
    private Button modelButton,wavButton,separate,cancel,exportZip,exportWavs;
    private Button[] preview=new Button[6];
    private ProgressBar progress;
    private Spinner threadChoice;
    private Spinner section;
    private boolean busy=false;
    private boolean extracting=false;
    private long startedAt=0;
    private String currentPhase="Ready";
    private int currentPss=0;
    private final Runnable clock=new Runnable(){
        @Override public void run(){
            if(extracting){
                status.setText(currentPhase+" · "+formatElapsed(System.nanoTime()-startedAt)+
                    " elapsed · memory ~"+currentPss+" MB");
                main.postDelayed(this,1000);
            }
        }
    };
    private MediaPlayer player;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        File models=new File(getFilesDir(),"models");
        model=new File(models,MODEL_FILENAME);
        if(!model.isFile() || model.length()<100_000_000L)model=null;
        render();
        SharedPreferences prefs=getSharedPreferences(PREFERENCES,MODE_PRIVATE);
        if(prefs.getBoolean("inference_active",false)){
            prefs.edit().putBoolean("inference_active",false).apply();
            status.setText("Previous six-stem run ended unexpectedly. Memory pressure or a native failure may have occurred. Try a shorter preview and report the stage shown before it closed.");
        }
    }
    private String formatElapsed(long nanos){
        long sec=Math.max(0,nanos/1_000_000_000L);
        return String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60);
    }
    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+.5f);}
    private GradientDrawable background(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);
        d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);
        t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout column(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;
    }
    private void gap(LinearLayout c,int size){
        c.addView(new View(this),new LinearLayout.LayoutParams(1,dp(size)));
    }
    private LinearLayout panel(LinearLayout content,String title){
        LinearLayout p=column();
        p.setPadding(dp(17),dp(16),dp(17),dp(12));
        p.setBackground(background(PANEL,17));
        content.addView(p,new LinearLayout.LayoutParams(-1,-2));
        p.addView(text(title,15,FG,true));gap(p,9);
        return p;
    }
    private Button button(String title){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);
        b.setTextSize(14);b.setTextColor(BG);
        b.setBackground(background(AQUA,13));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));
        params.bottomMargin=dp(8);b.setLayoutParams(params);
        return b;
    }
    private void render(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout body=column();body.setPadding(dp(18),dp(22),dp(18),dp(30));
        scroll.addView(body);setContentView(scroll);
        body.addView(text("DENNIS  /  STEM STUDIO",12,AQUA,true));gap(body,8);
        body.addView(text("6-Stem Laboratory",27,FG,true));
        body.addView(text("Experimental v0.1.8 · HT-Demucs · adds Guitar and Piano",12,MUTE,false));
        gap(body,17);

        LinearLayout p=panel(body,"01  Model · offline");
        modelLabel=text("",13,MUTE,false);p.addView(modelLabel);gap(p,7);
        modelButton=button("Install the 6-stem model");p.addView(modelButton);
        modelButton.setOnClickListener(v->pick(PICK_MODEL,"*/*"));
        p.addView(text("One large model does all six stems. Test 10 seconds first.",12,MUTE,false));
        gap(body,12);

        p=panel(body,"02  Source WAV");
        wavLabel=text("",13,MUTE,false);p.addView(wavLabel);gap(p,8);
        wavButton=button("Import WAV (full song accepted)");p.addView(wavButton);
        wavButton.setOnClickListener(v->pick(PICK_WAV,"audio/*"));
        gap(body,12);

        p=panel(body,"03  Choose processing mode");
        p.addView(text("The song is worked through in overlapping 7.8-second sections. Try a 60-second section first to assess processing time, or keep using a 10-second preview.",13,MUTE,false));
        gap(p,10);
        section=new Spinner(this);
        String[] segments={
            "FULL SONG · separate entire WAV",
            "60 seconds · 1:00–2:00",
            "60 seconds · 0:00–1:00",
            "10 seconds · 0:00–0:10",
            "10 seconds · 0:30–0:40",
            "10 seconds · 1:00–1:10",
            "10 seconds · 1:30–1:40",
            "10 seconds · 2:00–2:10",
            "10 seconds · 3:00–3:10"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item,segments);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        section.setAdapter(adapter);section.setSelection(5);
        p.addView(section,new LinearLayout.LayoutParams(-1,dp(48)));
        threadChoice=new Spinner(this);
        ArrayAdapter<String> threadAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
            new String[]{"4 threads · suggested start","2 threads","5 threads","6 threads"});
        threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        threadChoice.setAdapter(threadAdapter);
        p.addView(threadChoice,new LinearLayout.LayoutParams(-1,dp(48)));
        separate=button("Separate 6 stems · Start");p.addView(separate);
        cancel=button("Cancel after current AI section");p.addView(cancel);
        cancel.setOnClickListener(v->{
            SixStemNative.cancel();
            currentPhase="Cancelling after current AI section…";
            cancel.setEnabled(false);
        });
        separate.setOnClickListener(v->runSeparation());
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);p.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        status=text("Ready. Models are required before extraction.",13,MUTE,false);
        p.addView(status);gap(body,12);

        p=panel(body,"04  Audition and export");
        for(int i=0;i<6;i++){
            final int which=i;
            preview[i]=button("▶  Audition "+LABELS[i]);p.addView(preview[i]);
            preview[i].setOnClickListener(v->play(which));
        }
        exportZip=button("Export 6-stem ZIP for Master Room");p.addView(exportZip);
        exportZip.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE,(wav==null?"Song":safe(wav.getName()))+
                  "_6Stems_48kHz_24bit.zip");
            startActivityForResult(intent,PICK_ZIP);
        });
        exportWavs=button("Export 6 WAV files");p.addView(exportWavs);
        exportWavs.setOnClickListener(v->startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),PICK_FOLDER));
        gap(body,13);
        body.addView(text("Other is what the five named stems leave behind, so all six add back up to the song. Long songs may take several minutes.",12,MUTE,false));
        refresh();
    }
    private void refresh(){
        modelLabel.setText(model==null?"Select htdemucs_6s_fp16weights.onnx from the build's model download.":
             "6-stem model installed · "+(model.length()/1048576)+" MB");
        wavLabel.setText(wav==null?"No WAV imported. Stereo 44.1/48 kHz, max 12 minutes.":wav.getName());
        modelButton.setEnabled(!busy);
        cancel.setVisibility(extracting?View.VISIBLE:View.GONE);
        cancel.setEnabled(extracting);
        wavButton.setEnabled(!busy);section.setEnabled(!busy);
        separate.setEnabled(!busy && model!=null && wav!=null);
        boolean canExport=!busy && stems!=null;
        for(Button b:preview)b.setEnabled(canExport);
        exportZip.setEnabled(canExport);exportWavs.setEnabled(canExport);
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
    }
    private void state(boolean processing,String message){
        busy=processing;status.setText(message);refresh();
    }
    private void failure(String title,Throwable error){
        main.post(()->{
            finishTimer();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            state(false,title+": "+(error.getMessage()==null?
              error.getClass().getSimpleName():error.getMessage()));
        });
    }
    private void finishTimer(){
        extracting=false;
        main.removeCallbacks(clock);
    }
    private void pick(int req,String mime){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,req);
    }
    private String name(Uri uri){
        try(Cursor cursor=getContentResolver().query(uri,
          new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst())return cursor.getString(0);
        }catch(Exception ignored){}
        return "";
    }
    private String safe(String str){
        String cleaned=str.replaceAll("(?i)\\.(wav|wave)$","")
          .replaceAll("[^A-Za-z0-9_-]+","_");
        if(cleaned.length()>45)cleaned=cleaned.substring(0,45);
        return cleaned.isEmpty()?"Song":cleaned;
    }
    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req,int code,Intent data){
        super.onActivityResult(req,code,data);
        if(code!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(req==PICK_ZIP){zip(uri);return;}
        if(req==PICK_FOLDER){exportFolder(uri);return;}
        if(busy)return;
        final String original=name(uri);
        final boolean isWav=req==PICK_WAV;
        if(isWav && !original.toLowerCase(Locale.ROOT).endsWith(".wav")){
            state(false,"Choose an uncompressed WAV file");return;
        }
        if(!isWav && !original.toLowerCase(Locale.ROOT).endsWith(".onnx")){
            state(false,"Choose a local .onnx file");return;
        }
        state(true,"Importing "+(isWav?"WAV":"ONNX model")+" offline…");
        worker.execute(()->{
            File partial=null;
            try{
                File folder=new File(getFilesDir(),isWav?"input":"models");
                if(!folder.exists()&&!folder.mkdirs())throw new IOException("Cannot create storage");
                File finalFile=new File(folder,isWav?safe(original)+".wav":
                    MODEL_FILENAME);
                partial=new File(folder,finalFile.getName()+".partial");
                try(InputStream in=getContentResolver().openInputStream(uri);
                    OutputStream out=new FileOutputStream(partial)){
                    if(in==null)throw new IOException("Cannot read selected file");
                    byte[] buffer=new byte[131072];
                    long copied=0;int n;
                    long max=isWav?650_000_000L:600_000_000L;
                    while((n=in.read(buffer))!=-1){
                        copied+=n;
                        if(copied>max)throw new IOException("File exceeds allowed size");
                        out.write(buffer,0,n);
                    }
                }
                if(partial.length()<(isWav?44:1024*1024))
                    throw new IOException("Input appears truncated");
                if(!isWav && (partial.length()<100_000_000L||partial.length()>300_000_000L))
                    throw new IOException("That is not the 6-stem model");
                if(isWav){
                    byte[] head=new byte[12];
                    try(InputStream in=new FileInputStream(partial)){
                        if(in.read(head)!=12 ||
                          !"RIFF".equals(new String(head,0,4,java.nio.charset.StandardCharsets.US_ASCII)) ||
                          !"WAVE".equals(new String(head,8,4,java.nio.charset.StandardCharsets.US_ASCII)))
                           throw new IOException("Not a standard WAV file");
                    }
                }
                if(finalFile.exists()&&!finalFile.delete())throw new IOException("Cannot replace previous import");
                if(!partial.renameTo(finalFile))throw new IOException("Cannot finish import");
                main.post(()->{
                    if(req==PICK_MODEL)model=finalFile;
                    if(req==PICK_WAV){wav=finalFile;stems=null;stopPlayer();}
                    state(false,"Imported "+original+". Ready to test.");
                });
            }catch(Throwable error){
                if(partial!=null)partial.delete();
                failure("Import failed",error);
            }
        });
    }
    private void runSeparation(){
        if(busy||model==null||wav==null)return;
        stopPlayer();stems=null;
        final int mode=Math.max(0,Math.min(section.getSelectedItemPosition(),START.length-1));
        final int start=START[mode],duration=DURATIONS[mode];
        final int threads=THREADS[Math.max(0,Math.min(threadChoice.getSelectedItemPosition(),THREADS.length-1))];
        final String name=safe(wav.getName())+
            (duration==0?"_6StemFull":duration==60?"_6Stem60s"+start:"_6Stem10s"+start);
        File base=getExternalFilesDir(null)==null?getFilesDir():getExternalFilesDir(null);
        final File directory=new File(base,"6stem");
        state(true,duration==0?"Preparing full-song six-stem extraction…":
                           "Preparing "+duration+"-second preview…");
        progress.setProgress(0);
        startedAt=System.nanoTime();
        extracting=true;
        currentPhase="Loading first AI section";
        currentPss=0;
        main.removeCallbacks(clock);
        main.post(clock);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(()->{
            SharedPreferences prefs=getSharedPreferences(PREFERENCES,MODE_PRIVATE);
            try{
                if(!directory.exists()&&!directory.mkdirs())
                    throw new IOException("Cannot create output storage");
                StatFs store=new StatFs(directory.getAbsolutePath());
                if(store.getAvailableBytes()<150_000_000L)
                    throw new IOException("Free at least 150 MB. Full songs require more scratch storage.");
                // Synchronous marker survives system process kill; never confuse user cancellation.
                if(!prefs.edit().putBoolean("inference_active",true).commit())
                    throw new IOException("Cannot write crash-recovery marker");
                String[] result;
                try{
                    result=SixStemNative.separate(
                        model.getAbsolutePath(),wav.getAbsolutePath(),
                        directory.getAbsolutePath(),name,start,duration,threads,
                        (phase,chunk,total)->main.post(()->{
                            if(!extracting)return;
                            int percent;
                            String next;
                            if(phase==0){
                                percent=(int)(90.0*chunk/total);
                                next="Separating · section "+(chunk+1)+"/"+total;
                            }else if(phase==1){
                                percent=90+chunk;
                                next="Preparing stem "+(chunk+1)+"/6";
                            }else if(phase==2){
                                percent=96+chunk*2/3;
                                next="Final WAV export "+(chunk+1)+"/6";
                            }else{
                                percent=100;
                                next="Six-stem extraction complete";
                            }
                            progress.setProgress(Math.min(100,percent));
                            Debug.MemoryInfo mem=new Debug.MemoryInfo();
                            Debug.getMemoryInfo(mem);
                            currentPss=mem.getTotalPss()/1024;
                            currentPhase=next;
                            status.setText(currentPhase+" · "+
                                formatElapsed(System.nanoTime()-startedAt)+
                                " · memory ~"+currentPss+" MB");
                        }));
                }finally{
                    prefs.edit().putBoolean("inference_active",false).commit();
                }
                if(result==null||result.length!=7)
                    throw new IOException("No six-stem results");
                for(int i=0;i<6;i++){
                    File out=new File(result[i]);
                    if(!out.isFile()||out.length()<44)
                        throw new IOException("Missing "+LABELS[i]+" WAV");
                }
                final String elapsed=formatElapsed(System.nanoTime()-startedAt);
                main.post(()->{
                    stems=result;
                    finishTimer();
                    progress.setProgress(100);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    state(false,result[6]+" · Processing time "+elapsed);
                });
            }catch(Throwable error){failure("6-stem extraction stopped",error);}
        });
    }
    private void play(int which){
        if(stems==null||busy)return;
        try{
            stopPlayer();
            player=new MediaPlayer();player.setDataSource(stems[which]);
            player.prepare();player.start();
            status.setText("Playing "+LABELS[which]);
        }catch(Exception error){stopPlayer();status.setText("Preview error: "+error.getMessage());}
    }
    private void stopPlayer(){if(player!=null){player.release();player=null;}}
    private void zip(Uri destination){
        if(busy||stems==null)return;
        String[] paths=stems.clone();
        state(true,"Exporting 6-stem ZIP…");
        worker.execute(()->{
            try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){
                if(out==null)throw new IOException("Destination unavailable");
                try(ZipOutputStream z=new ZipOutputStream(out)){
                    z.setLevel(Deflater.BEST_SPEED);
                    byte[] buffer=new byte[131072];
                    for(int i=0;i<6;i++){
                        File wavFile=new File(paths[i]);
                        z.putNextEntry(new ZipEntry(wavFile.getName()));
                        try(InputStream in=new FileInputStream(wavFile)){
                            int n;while((n=in.read(buffer))!=-1)z.write(buffer,0,n);
                        }
                        z.closeEntry();
                    }
                }
                main.post(()->state(false,"Exported 6-stem ZIP for Master Room."));
            }catch(Throwable error){failure("ZIP export failed",error);}
        });
    }
    private void exportFolder(Uri tree){
        if(busy||stems==null)return;
        String[] paths=stems.clone();
        state(true,"Exporting 6 WAV stems…");
        worker.execute(()->{
            try{
                ContentResolver resolver=getContentResolver();
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
                for(int i=0;i<6;i++){
                    File file=new File(paths[i]);
                    Uri dest=DocumentsContract.createDocument(resolver,parent,"audio/wav",file.getName());
                    if(dest==null)throw new IOException("Cannot create WAV");
                    try(InputStream in=new FileInputStream(file);
                        OutputStream out=resolver.openOutputStream(dest,"w")){
                        if(out==null)throw new IOException("Folder is not writable");
                        byte[] buffer=new byte[131072];int n;
                        while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    }
                }
                main.post(()->state(false,"Exported 4 aligned 48 kHz / 24-bit WAV stems."));
            }catch(Throwable error){failure("WAV export failed",error);}
        });
    }
    @Override protected void onDestroy(){
        if(extracting)SixStemNative.cancel();
        finishTimer();stopPlayer();worker.shutdown();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }
}
