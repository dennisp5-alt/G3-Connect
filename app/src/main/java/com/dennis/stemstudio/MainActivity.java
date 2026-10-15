package com.dennis.stemstudio;

import android.app.Activity;
import android.content.Intent;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO=1, PICK_MODEL=2, PICK_EXPORT=3;
    private static final int BG=Color.rgb(14,18,24), PANEL=Color.rgb(27,34,43);
    private static final int FG=Color.rgb(242,247,250), MUTE=Color.rgb(153,169,181);
    private static final int AQUA=Color.rgb(97,207,212);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView modelText, audioText, statusText, heading;
    private Button importAudio, importModel, separate, vocals, music, export;
    private ProgressBar spinner;
    private File audioFile, modelFile;
    private String[] stemPaths;
    private MediaPlayer player;
    private boolean busy=false;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        modelFile=new File(new File(getFilesDir(),"models"),"uvr-model.onnx");
        if(!modelFile.isFile() || modelFile.length()<1024*1024) modelFile=null;
        renderUI();
    }

    private GradientDrawable background(int fill,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fill);d.setCornerRadius(dp(radius));return d;
    }
    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+.5f);}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private void gap(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(String name){
        Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(15);
        b.setTextColor(BG); b.setBackground(background(AQUA,14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.bottomMargin=dp(9);
        b.setLayoutParams(p);return b;
    }
    private LinearLayout panel(LinearLayout root){
        LinearLayout p=vertical();p.setPadding(dp(18),dp(18),dp(18),dp(12));
        p.setBackground(background(PANEL,19));
        root.addView(p,new LinearLayout.LayoutParams(-1,-2));return p;
    }
    private void renderUI(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout body=vertical();body.setPadding(dp(20),dp(24),dp(20),dp(24));
        scroll.addView(body);setContentView(scroll);
        body.addView(text("DENNIS  /  STEM STUDIO",12,AQUA,true));gap(body,10);
        heading=text("Hear every layer.",29,FG,true);body.addView(heading);
        body.addView(text("Offline · Quality-first · 2-stem engine test",13,MUTE,false));gap(body,25);

        LinearLayout p=panel(body);
        p.addView(text("01   Separation model",15,FG,true));gap(p,6);
        modelText=text("",13,MUTE,false);p.addView(modelText);gap(p,13);
        importModel=button("Install local UVR model (.onnx)");p.addView(importModel);
        importModel.setOnClickListener(v->picker(PICK_MODEL,"*/*"));
        gap(body,13);

        p=panel(body);p.addView(text("02   Source audio",15,FG,true));gap(p,6);
        audioText=text("",13,MUTE,false);p.addView(audioText);gap(p,13);
        importAudio=button("Import stereo WAV");p.addView(importAudio);
        importAudio.setOnClickListener(v->picker(PICK_AUDIO,"audio/*"));
        gap(body,13);

        p=panel(body);p.addView(text("03   Separate",15,FG,true));gap(p,8);
        p.addView(text("Vocals + instrumental, processed locally on your phone. No EQ, compression or loudness processing.",13,MUTE,false));
        gap(p,12);separate=button("Separate audio · Highest quality");p.addView(separate);
        separate.setOnClickListener(v->runSeparation());
        spinner=new ProgressBar(this);spinner.setVisibility(View.GONE);
        p.addView(spinner,new LinearLayout.LayoutParams(-1,dp(44)));
        statusText=text("Ready to import",13,MUTE,false);p.addView(statusText);gap(body,13);

        p=panel(body);p.addView(text("04   Results",15,FG,true));gap(p,12);
        vocals=button("▶  Audition vocals");p.addView(vocals);
        music=button("▶  Audition instrumental");p.addView(music);
        export=button("Export both WAV stems");p.addView(export);
        vocals.setOnClickListener(v->play(0));music.setOnClickListener(v->play(1));
        export.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);startActivityForResult(i,PICK_EXPORT);});
        gap(body,18);
        body.addView(text("TEST BUILD · Validate separation quality and device performance before expanding to four stems.",11,MUTE,false));
        refresh();
    }
    private void refresh(){
        modelText.setText(modelFile==null ? "Not installed. Select a compatible MDX-Net/UVR ONNX model." :
            "Installed locally · "+(modelFile.length()/1048576)+" MB");
        audioText.setText(audioFile==null ? "No file selected · 44.1/48 kHz WAV, up to 12 minutes" : audioFile.getName());
        importModel.setEnabled(!busy);importAudio.setEnabled(!busy);
        separate.setEnabled(!busy && modelFile!=null && audioFile!=null);
        boolean ready=!busy && stemPaths!=null && stemPaths.length>=2;
        vocals.setEnabled(ready);music.setEnabled(ready);export.setEnabled(ready);
        spinner.setVisibility(busy?View.VISIBLE:View.GONE);
    }
    private void picker(int request,String type){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i,request);
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null && c.moveToFirst()) return c.getString(0);
        }catch(Exception ignored){}
        return "import.wav";
    }
    private String safeName(String name){
        String s=name.replaceAll("(?i)\\.(wav|wave)$","").replaceAll("[^A-Za-z0-9_-]+","_");
        return s.length()>60?s.substring(0,60):(s.isEmpty()?"Song":s);
    }
    private void state(boolean processing,String message){
        busy=processing;statusText.setText(message);refresh();
    }
    private void onFailure(String prefix,Throwable t){
        main.post(()->state(false,prefix+": "+(t.getMessage()!=null?t.getMessage():t.getClass().getSimpleName())));
    }
    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);
        if(result!=RESULT_OK || intent==null || intent.getData()==null)return;
        Uri selected=intent.getData();
        if(request==PICK_EXPORT){exportFolder(selected);return;}
        if(busy)return;
        boolean isModel=request==PICK_MODEL;
        String chosen=displayName(selected);
        if(isModel && !chosen.toLowerCase(java.util.Locale.ROOT).endsWith(".onnx")){
            state(false,"Select a .onnx model file");return;
        }
        if(!isModel && !chosen.toLowerCase(java.util.Locale.ROOT).endsWith(".wav")){
            state(false,"This engine test accepts WAV input only");return;
        }
        state(true,"Copying "+(isModel?"model":"audio")+" to private offline storage…");
        worker.execute(()->{
            try{
                File dir=new File(getFilesDir(),isModel?"models":"imports");
                if(!dir.exists() && !dir.mkdirs())throw new IOException("Cannot create import directory");
                File dest=new File(dir,isModel?"uvr-model.onnx":safeName(chosen)+".wav");
                File temporary=new File(dir,dest.getName()+".partial");
                try(InputStream in=getContentResolver().openInputStream(selected);
                    OutputStream out=new FileOutputStream(temporary)){
                    if(in==null)throw new IOException("Selected file cannot be opened");
                    byte[] buf=new byte[131072];int n;long count=0;
                    while((n=in.read(buf))!=-1){count+=n;
                        if(count>(isModel?650_000_000L:550_000_000L))throw new IOException("Input is too large");
                        out.write(buf,0,n);
                    }
                }
                if(temporary.length()<(isModel?1024*1024:44)) throw new IOException("File appears empty or truncated");
                if(!isModel){
                    byte[] header=new byte[12];
                    try(FileInputStream in=new FileInputStream(temporary)){
                        if(in.read(header)!=12 || !new String(header,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") ||
                            !new String(header,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"))
                            throw new IOException("Not a standard RIFF WAV file");
                    }
                }
                if(dest.exists() && !dest.delete())throw new IOException("Cannot replace previous file");
                if(!temporary.renameTo(dest))throw new IOException("Cannot finalise imported file");
                main.post(()->{
                    if(isModel)modelFile=dest;
                    else {audioFile=dest;stemPaths=null;stopPlayer();}
                    state(false,isModel?"UVR model installed. Runs offline from now on.":"Audio imported. Ready to separate.");
                });
            }catch(Throwable t){onFailure("Import failed",t);}
        });
    }
    private void runSeparation(){
        if(busy || modelFile==null || audioFile==null)return;
        stopPlayer();stemPaths=null;state(true,"Separating locally. Keep the app open; no artificial progress estimate.");
        File external=getExternalFilesDir(null);
        File outDir=new File(external!=null?external:getFilesDir(),"stem_exports");
        worker.execute(()->{
            try{
                if(!outDir.exists() && !outDir.mkdirs()) throw new IOException("Cannot create output folder");
                String[] output=NativeSeparator.separate(modelFile.getAbsolutePath(),audioFile.getAbsolutePath(),
                        outDir.getAbsolutePath(),safeName(audioFile.getName()));
                if(output==null || output.length<3)throw new IOException("No stems returned");
                if(!new File(output[0]).isFile() || !new File(output[1]).isFile())throw new IOException("Stem export missing");
                main.post(()->{stemPaths=output;state(false,output[2]);});
            }catch(Throwable t){onFailure("Separation failed",t);}
        });
    }
    private void play(int stem){
        if(stemPaths==null||busy)return;
        try{
            stopPlayer();player=new MediaPlayer();player.setDataSource(stemPaths[stem]);player.prepare();player.start();
            statusText.setText("Playing "+(stem==0?"vocals":"instrumental")+" · tap again to restart");
        }catch(Exception ex){state(false,"Preview failed: "+ex.getMessage());stopPlayer();}
    }
    private void stopPlayer(){if(player!=null){try{player.release();}catch(Exception ignored){} player=null;}}
    private void exportFolder(Uri tree){
        if(stemPaths==null || busy)return;
        String[] paths=stemPaths.clone();state(true,"Writing stems to selected folder…");
        worker.execute(()->{
            try{
                ContentResolver resolver=getContentResolver();
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                for(int i=0;i<2;i++){
                    File src=new File(paths[i]);
                    Uri dest=DocumentsContract.createDocument(resolver,parent,"audio/wav",src.getName());
                    if(dest==null)throw new IOException("Cannot create "+src.getName());
                    try(InputStream in=new FileInputStream(src);OutputStream out=resolver.openOutputStream(dest,"w")){
                        if(out==null)throw new IOException("Destination refused file stream");
                        byte[] buf=new byte[131072];int n;
                        while((n=in.read(buf))!=-1)out.write(buf,0,n);
                    }
                }
                main.post(()->state(false,"Exported both 24-bit stereo WAV stems to selected folder."));
            }catch(Throwable t){onFailure("Export failed (check folder for partial files)",t);}
        });
    }
    @Override protected void onDestroy(){stopPlayer();worker.shutdown();super.onDestroy();}
}
