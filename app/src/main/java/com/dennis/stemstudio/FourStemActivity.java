package com.dennis.stemstudio;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Debug;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Experimental four-stem screen; original two-stem screen and native code unchanged. */
public final class FourStemActivity extends Activity {
    private static final String PREF="fourstem-lab-crash-checkpoint";
    private static final int PICK_MODEL=101,PICK_AUDIO=102,PICK_ZIP=103,PICK_FOLDER=104;
    private static final int BG=Color.rgb(14,18,24),PANEL=Color.rgb(27,34,43);
    private static final int FG=Color.rgb(242,247,250),MUTE=Color.rgb(154,168,183);
    private static final int AQUA=Color.rgb(97,207,212);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final String[] LABELS={"Vocals","Drums","Bass","Other"};
    private File model, audio;
    private String[] output;
    private boolean busy=false;
    private boolean processing=false;
    private MediaPlayer player;
    private TextView modelText,audioText,status;
    private Button selectModel,selectAudio,separate,cancel,exportZip,exportFolder;
    private final Button[] previews=new Button[4];
    private ProgressBar progress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        model=new File(new File(getFilesDir(),"models"),"htdemucs-4stem.onnx");
        if(!model.isFile() || model.length()<100_000_000L)model=null;
        showScreen();
        if(getSharedPreferences(PREF,MODE_PRIVATE).getBoolean("inference_active",false)){
            state(false,"Previous four-stem test ended unexpectedly. Memory pressure or a native crash is possible. Try the short test and report whether it closes before or after chunk 1.");
        }
    }
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable surface(int color,int round){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(round));return d;
    }
    private TextView label(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical(){
        LinearLayout v=new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);return v;
    }
    private void gap(LinearLayout box,int space){
        box.addView(new View(this),new LinearLayout.LayoutParams(1,dp(space)));
    }
    private Button button(String text){
        Button b=new Button(this);b.setAllCaps(false);b.setText(text);b.setTextSize(14);
        b.setTextColor(BG);b.setBackground(surface(AQUA,14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));
        p.bottomMargin=dp(9);b.setLayoutParams(p);return b;
    }
    private LinearLayout panel(LinearLayout parent,String title){
        LinearLayout p=vertical();p.setPadding(dp(16),dp(16),dp(16),dp(12));
        p.setBackground(surface(PANEL,17));
        parent.addView(p,new LinearLayout.LayoutParams(-1,-2));
        p.addView(label(title,16,FG,true));gap(p,10);return p;
    }
    private void showScreen(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout content=vertical();content.setPadding(dp(20),dp(23),dp(20),dp(26));
        scroll.addView(content);setContentView(scroll);
        content.addView(label("DENNIS  /  STEM STUDIO",12,AQUA,true));gap(content,8);
        content.addView(label("Four-Stem Laboratory",27,FG,true));
        content.addView(label("Experimental v0.1.3 · memory-safe short-test · offline HT-Demucs",12,MUTE,false));gap(content,18);
        LinearLayout p=panel(content,"01   Four-stem model");
        modelText=label("",13,MUTE,false);p.addView(modelText);gap(p,9);
        selectModel=button("Install HT-Demucs ONNX model");p.addView(selectModel);
        selectModel.setOnClickListener(v->selectFile(PICK_MODEL,"*/*"));
        gap(content,12);
        p=panel(content,"02   Audio source");
        audioText=label("",13,MUTE,false);p.addView(audioText);gap(p,9);
        selectAudio=button("Import WAV (44.1 / 48 kHz)");p.addView(selectAudio);
        selectAudio.setOnClickListener(v->selectFile(PICK_AUDIO,"audio/*"));
        gap(content,12);
        p=panel(content,"03   Separate 4 sources");
        p.addView(label("Select any full-length WAV. This first test automatically processes only its first 10 seconds. No editing or trimming needed. Four aligned 48 kHz / 24-bit stems.",13,MUTE,false));
        gap(p,11);
        separate=button("Test first 10 seconds · 4 stems");p.addView(separate);
        separate.setOnClickListener(v->runSeparation());
        cancel=button("Cancel processing");p.addView(cancel);
        cancel.setOnClickListener(v->{FourStemNative.cancel();status.setText("Cancelling after current inference chunk…");cancel.setEnabled(false);});
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);p.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        status=label("Ready for model and audio import",13,MUTE,false);p.addView(status);
        gap(content,12);
        p=panel(content,"04   Audition and export");
        for(int i=0;i<4;i++){
            final int source=i;
            previews[i]=button("▶  Preview "+LABELS[i]);p.addView(previews[i]);
            previews[i].setOnClickListener(v->play(source));
        }
        exportFolder=button("Export all 4 WAV files");p.addView(exportFolder);
        exportFolder.setOnClickListener(v->startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),PICK_FOLDER));
        exportZip=button("Export ZIP for Dennis Stem Lab");p.addView(exportZip);
        exportZip.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE,(audio==null?"Song":safeName(audio.getName()))+
                    "_Stems_48kHz_24bit.zip");
            startActivityForResult(intent,PICK_ZIP);
        });
        gap(content,12);
        content.addView(label("Full-song four-stem processing is disabled in this diagnostic version until the Demucs model passes on-device RAM testing. The existing full-song 2-stem mode is unchanged.",12,MUTE,false));
        refresh();
    }
    private void refresh(){
        modelText.setText(model==null?"Select htdemucs_fp16weights.onnx (166 MB) or htdemucs.onnx (316 MB). UVR models are not compatible.":
               "Installed offline · "+(model.length()/1048576)+" MiB");
        audioText.setText(audio==null?"No WAV selected. Maximum 12 minutes.":audio.getName());
        selectModel.setEnabled(!busy);selectAudio.setEnabled(!busy);
        separate.setEnabled(!busy && model!=null && audio!=null);
        cancel.setVisibility(processing?View.VISIBLE:View.GONE);
        cancel.setEnabled(processing);
        boolean ready=output!=null && output.length==5 && !busy;
        for(Button b:previews)b.setEnabled(ready);
        exportZip.setEnabled(ready);exportFolder.setEnabled(ready);
        progress.setVisibility(processing?View.VISIBLE:View.GONE);
    }
    private void state(boolean working,String message){
        busy=working;processing=working && message.startsWith("Separat");
        status.setText(message);refresh();
    }
    private void fail(String name,Throwable error){
        ui.post(()->{
            processing=false;
            state(false,name+": "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));
        });
    }
    private void selectFile(int request,String type){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(type);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,request);
    }
    private String fileName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},
                null,null,null)){
            if(c!=null && c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "song.wav";
    }
    private String safeName(String name){
        String s=name.replaceAll("(?i)\\.(wav|wave)$","").replaceAll("[^A-Za-z0-9_-]+","_");
        if(s.length()>55)s=s.substring(0,55);
        return s.isEmpty()?"Song":s;
    }
    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int req,int code,Intent data){
        super.onActivityResult(req,code,data);
        if(code!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(req==PICK_ZIP){exportZip(uri);return;}
        if(req==PICK_FOLDER){exportWavs(uri);return;}
        if(busy)return;
        final boolean isModel=req==PICK_MODEL;
        final String filename=fileName(uri);
        if(!filename.toLowerCase(java.util.Locale.ROOT).endsWith(isModel?".onnx":".wav")){
            state(false,isModel?"Select a .onnx file":"Select an uncompressed .wav file");return;
        }
        state(true,"Importing "+(isModel?"HT-Demucs model":"WAV")+" into offline storage…");
        executor.execute(()->{
            File temporary=null;
            try{
                File dir=new File(getFilesDir(),isModel?"models":"fourstem-input");
                if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create import directory");
                File dest=new File(dir,isModel?"htdemucs-4stem.onnx":safeName(filename)+".wav");
                temporary=new File(dir,dest.getName()+".partial");
                final long limit=isModel?450_000_000L:650_000_000L;
                try(InputStream in=getContentResolver().openInputStream(uri);
                    OutputStream out=new FileOutputStream(temporary)){
                    if(in==null)throw new IOException("Cannot open selected file");
                    byte[] buffer=new byte[131072];int n;long size=0;
                    while((n=in.read(buffer))!=-1){
                        size+=n;if(size>limit)throw new IOException("Selected file exceeds size limit");
                        out.write(buffer,0,n);
                    }
                }
                if(isModel){
                    if(temporary.length()<100_000_000L)throw new IOException("HT-Demucs ONNX model is expected to be at least 100 MB; this looks like a UVR model or partial download.");
                }else{
                    byte[] h=new byte[12];
                    try(InputStream in=new FileInputStream(temporary)){
                        if(in.read(h)!=12 || !"RIFF".equals(new String(h,0,4,StandardCharsets.US_ASCII)) ||
                            !"WAVE".equals(new String(h,8,4,StandardCharsets.US_ASCII)))
                            throw new IOException("Not a RIFF/WAV file");
                    }
                }
                if(dest.exists()&&!dest.delete())throw new IOException("Cannot replace old import");
                if(!temporary.renameTo(dest))throw new IOException("Cannot complete import");
                ui.post(()->{
                    if(isModel)model=dest;
                    else{audio=dest;output=null;stopPlayer();}
                    state(false,isModel?"HT-Demucs model imported. Model shape is checked at inference.":"Full WAV imported. The first 10 seconds will be tested automatically.");
                });
            }catch(Throwable e){
                if(temporary!=null)temporary.delete();
                fail("Import failed",e);
            }
        });
    }
    private void runSeparation(){
        if(busy||audio==null||model==null)return;
        stopPlayer();output=null;
        File folder=new File(getExternalFilesDir(null)==null?getFilesDir():getExternalFilesDir(null),
                "experimental-fourstem");
        state(true,"Separating first 10 seconds: loading model and checking memory…");
        progress.setProgress(0);
        executor.execute(()->{
            try{
                if(!folder.exists() && !folder.mkdirs())throw new IOException("Cannot create stem folder");
                StatFs fs=new StatFs(folder.getAbsolutePath());
                long required=80_000_000L; // 4 × 10-second stereo PCM24 plus staging
                if(fs.getAvailableBytes()<required)throw new IOException("Insufficient free space for preview stems: free at least "+
                        (required/1_000_000)+" MB");
                // A durable marker survives Android low-memory process kills, which cannot be caught.
                SharedPreferences prefs=getSharedPreferences(PREF,MODE_PRIVATE);
                prefs.edit().putBoolean("inference_active",true).putLong("started",System.currentTimeMillis()).commit();
                String[] result;
                try {
                    result=FourStemNative.separate(model.getAbsolutePath(),audio.getAbsolutePath(),
                        folder.getAbsolutePath(),safeName(audio.getName())+"_10sPreview",10,(done,total)->ui.post(()->{
                            progress.setProgress(total==0?0:(done*100/total));
                            Debug.MemoryInfo mem=new Debug.MemoryInfo();
                            Debug.getMemoryInfo(mem);
                            status.setText((done==0?"Loading model · ":("Separated chunk "+done+"/"+total+" · "))+
                                "process PSS ~"+(mem.getTotalPss()/1024)+" MB");
                        }));
                } finally {
                    prefs.edit().putBoolean("inference_active",false).commit();
                }
                if(result==null || result.length!=5)throw new IOException("Four-stem engine returned no files");
                for(int i=0;i<4;i++)if(!new File(result[i]).isFile())
                    throw new IOException("Missing "+LABELS[i]+" output");
                ui.post(()->{output=result;processing=false;state(false,result[4]);});
            }catch(Throwable e){fail("Four-stem processing stopped",e);}
        });
    }
    private void play(int index){
        if(output==null||busy)return;
        try{
            stopPlayer();player=new MediaPlayer();player.setDataSource(output[index]);
            player.prepare();player.start();
            status.setText("Playing "+LABELS[index]+" · select another stem to switch");
        }catch(Exception ex){stopPlayer();state(false,"Preview failed: "+ex.getMessage());}
    }
    private void stopPlayer(){
        if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
    }
    private void exportZip(Uri uri){
        if(busy||output==null)return;
        String[] paths=output.clone();state(true,"Writing four-stem ZIP…");
        executor.execute(()->{
            try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IOException("Cannot open destination ZIP");
                try(ZipOutputStream zip=new ZipOutputStream(out)){
                    zip.setLevel(Deflater.BEST_SPEED);
                    byte[] buffer=new byte[131072];
                    for(int i=0;i<4;i++){
                        File src=new File(paths[i]);
                        if(!src.isFile())throw new IOException("Stem no longer available: "+LABELS[i]);
                        zip.putNextEntry(new ZipEntry(src.getName()));
                        try(InputStream in=new FileInputStream(src)){
                            int n;while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);
                        }
                        zip.closeEntry();
                    }
                    zip.finish();
                }
                ui.post(()->state(false,"Exported all four stereo 48 kHz / 24-bit WAV stems to ZIP."));
            }catch(Throwable e){fail("ZIP export failed (delete incomplete ZIP)",e);}
        });
    }
    private void exportWavs(Uri tree){
        if(busy||output==null)return;
        String[] paths=output.clone();state(true,"Exporting WAV files…");
        executor.execute(()->{
            try{
                ContentResolver resolver=getContentResolver();
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
                for(int i=0;i<4;i++){
                    File src=new File(paths[i]);
                    Uri dest=DocumentsContract.createDocument(resolver,parent,"audio/wav",src.getName());
                    if(dest==null)throw new IOException("Cannot create "+src.getName());
                    try(InputStream in=new FileInputStream(src);
                        OutputStream out=resolver.openOutputStream(dest,"w")){
                        if(out==null)throw new IOException("Destination not writable");
                        byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                    }
                }
                ui.post(()->state(false,"Exported all four aligned WAV stems."));
            }catch(Throwable e){fail("WAV export failed",e);}
        });
    }
    @Override protected void onDestroy(){
        FourStemNative.cancel();stopPlayer();executor.shutdown();
        super.onDestroy();
    }
}
