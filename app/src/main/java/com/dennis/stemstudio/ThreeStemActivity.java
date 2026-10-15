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

/** Preview gate for three-stem experiments; does not change the old 2-stem screen. */
public final class ThreeStemActivity extends Activity {
    private static final int PICK_VOCAL=101, PICK_DRUM=102, PICK_WAV=103,
       PICK_ZIP=104, PICK_FOLDER=105;
    private static final int BG=Color.rgb(14,18,24), PANEL=Color.rgb(27,34,43),
       FG=Color.rgb(242,247,250), MUTE=Color.rgb(153,169,181),
       AQUA=Color.rgb(97,207,212);
    private static final String[] LABELS={"Vocals","Drums","Other"};
    private static final int[] START={0,30,60,90,120,180};
    private static final String DRUM_FILENAME="kuielab_b_drums_sherpa.onnx";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private File vocal,drum,wav;
    private String[] stems;
    private TextView vocalLabel,drumLabel,wavLabel,status;
    private Button vocalButton,drumButton,wavButton,separate,exportZip,exportWavs;
    private Button[] preview=new Button[3];
    private ProgressBar progress;
    private Spinner section;
    private boolean busy=false;
    private MediaPlayer player;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        File models=new File(getFilesDir(),"models");
        vocal=new File(models,"uvr-model.onnx");
        drum=new File(models,DRUM_FILENAME);
        if(!vocal.isFile() || vocal.length()<1024*1024)vocal=null;
        if(!drum.isFile() || drum.length()<15_000_000L)drum=null;
        render();
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
        body.addView(text("3-Stem Laboratory",27,FG,true));
        body.addView(text("Experimental v0.1.2 · lightweight MDX-Net drums",12,MUTE,false));
        gap(body,17);

        LinearLayout p=panel(body,"01  Models · both offline");
        vocalLabel=text("",13,MUTE,false);p.addView(vocalLabel);gap(p,7);
        vocalButton=button("Install original vocal UVR model");p.addView(vocalButton);
        vocalButton.setOnClickListener(v->pick(PICK_VOCAL,"*/*"));
        gap(p,7);
        drumLabel=text("",13,MUTE,false);p.addView(drumLabel);gap(p,7);
        drumButton=button("Install prepared KUIELab B drum model");p.addView(drumButton);
        drumButton.setOnClickListener(v->pick(PICK_DRUM,"*/*"));
        gap(body,12);

        p=panel(body,"02  Source WAV");
        wavLabel=text("",13,MUTE,false);p.addView(wavLabel);gap(p,8);
        wavButton=button("Import WAV (full song accepted)");p.addView(wavButton);
        wavButton.setOnClickListener(v->pick(PICK_WAV,"audio/*"));
        gap(body,12);

        p=panel(body,"03  Select test section");
        p.addView(text("Choose any part of a normal song. We separate 10 seconds first to measure speed without risking an expensive full-song attempt.",13,MUTE,false));
        gap(p,10);
        section=new Spinner(this);
        String[] segments={
            "0:00 to 0:10","0:30 to 0:40","1:00 to 1:10",
            "1:30 to 1:40","2:00 to 2:10","3:00 to 3:10"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item,segments);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        section.setAdapter(adapter);section.setSelection(2);
        p.addView(section,new LinearLayout.LayoutParams(-1,dp(48)));
        separate=button("Test 3-stem extraction");p.addView(separate);
        separate.setOnClickListener(v->runSeparation());
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);p.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        status=text("Ready. Models are required before extraction.",13,MUTE,false);
        p.addView(status);gap(body,12);

        p=panel(body,"04  Audition and export");
        for(int i=0;i<3;i++){
            final int which=i;
            preview[i]=button("▶  Audition "+LABELS[i]);p.addView(preview[i]);
            preview[i].setOnClickListener(v->play(which));
        }
        exportZip=button("Export 3-stem ZIP for Stem Lab");p.addView(exportZip);
        exportZip.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE,(wav==null?"Song":safe(wav.getName()))+
                  "_3Stems_48kHz_24bit.zip");
            startActivityForResult(intent,PICK_ZIP);
        });
        exportWavs=button("Export 3 WAV files");p.addView(exportWavs);
        exportWavs.setOnClickListener(v->startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),PICK_FOLDER));
        gap(body,13);
        body.addView(text("This test runs two light models sequentially: the original voice model then a drum specialist. It is not yet a full-song release. The working 2-stem mode stays unchanged.",12,MUTE,false));
        refresh();
    }
    private void refresh(){
        vocalLabel.setText(vocal==null?"Vocal model not installed.":
             "Vocals model installed · "+(vocal.length()/1048576)+" MB");
        drumLabel.setText(drum==null?"Select the prepared kuielab_b_drums_sherpa.onnx file (approximately 22 MB).":
             "Drums specialist installed · "+(drum.length()/1048576)+" MB");
        wavLabel.setText(wav==null?"No WAV imported. Stereo 44.1/48 kHz, max 12 minutes.":wav.getName());
        vocalButton.setEnabled(!busy);drumButton.setEnabled(!busy);
        wavButton.setEnabled(!busy);section.setEnabled(!busy);
        separate.setEnabled(!busy && vocal!=null && drum!=null && wav!=null);
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
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            state(false,title+": "+(error.getMessage()==null?
              error.getClass().getSimpleName():error.getMessage()));
        });
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
        if(req==PICK_DRUM && !original.equalsIgnoreCase(DRUM_FILENAME)){
            state(false,"The drum model must be named "+DRUM_FILENAME+
              ". Download the prepared model from the experiment build artifact.");return;
        }
        state(true,"Importing "+(isWav?"WAV":"ONNX model")+" offline…");
        worker.execute(()->{
            File partial=null;
            try{
                File folder=new File(getFilesDir(),isWav?"input":"models");
                if(!folder.exists()&&!folder.mkdirs())throw new IOException("Cannot create storage");
                File finalFile=new File(folder,isWav?safe(original)+".wav":
                    (req==PICK_DRUM?DRUM_FILENAME:"uvr-model.onnx"));
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
                if(req==PICK_DRUM &&
                   (partial.length()<19_000_000L||partial.length()>27_000_000L))
                    throw new IOException("Unexpected drum model size");
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
                    if(req==PICK_VOCAL)vocal=finalFile;
                    if(req==PICK_DRUM)drum=finalFile;
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
        if(busy||vocal==null||drum==null||wav==null)return;
        stopPlayer();stems=null;
        final int start=START[Math.max(0,Math.min(section.getSelectedItemPosition(),START.length-1))];
        final String filename=safe(wav.getName())+"_3StemPreview"+start+"s";
        File base=getExternalFilesDir(null)==null?getFilesDir():getExternalFilesDir(null);
        final File directory=new File(base,"3stem-experiment");
        state(true,"Preparing two-pass three-stem test…");
        progress.setProgress(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(()->{
            try{
                if(!directory.exists()&&!directory.mkdirs())throw new IOException("Cannot create output storage");
                StatFs store=new StatFs(directory.getAbsolutePath());
                if(store.getAvailableBytes()<100_000_000L)
                   throw new IOException("Free at least 100 MB of storage");
                long startMs=System.nanoTime();
                String[] result=ThreeStemNative.separate(
                    vocal.getAbsolutePath(),drum.getAbsolutePath(),wav.getAbsolutePath(),
                    directory.getAbsolutePath(),filename,start,
                    phase->main.post(()->{
                        progress.setProgress(phase*33);
                        String[] phases={"Loading vocals model and extracting vocal stem…",
                            "Vocals complete · separating drums…",
                            "Drums complete · aligning and exporting 24-bit WAV…",
                            "Three-stem export complete."};
                        status.setText(phases[Math.max(0,Math.min(phase,3))]);
                    }));
                if(result==null||result.length!=4)throw new IOException("No three-stem results");
                for(int i=0;i<3;i++){
                    File f=new File(result[i]);
                    if(!f.isFile()||f.length()<44)
                        throw new IOException("Missing stem: "+LABELS[i]);
                }
                long elapsed=(System.nanoTime()-startMs)/1_000_000_000L;
                main.post(()->{
                    stems=result;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    state(false,result[3]+" · Processing time "+elapsed+" seconds");
                });
            }catch(Throwable error){failure("3-stem test failed",error);}
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
        state(true,"Exporting 3-stem ZIP…");
        worker.execute(()->{
            try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){
                if(out==null)throw new IOException("Destination unavailable");
                try(ZipOutputStream z=new ZipOutputStream(out)){
                    z.setLevel(Deflater.BEST_SPEED);
                    byte[] buffer=new byte[131072];
                    for(int i=0;i<3;i++){
                        File wavFile=new File(paths[i]);
                        z.putNextEntry(new ZipEntry(wavFile.getName()));
                        try(InputStream in=new FileInputStream(wavFile)){
                            int n;while((n=in.read(buffer))!=-1)z.write(buffer,0,n);
                        }
                        z.closeEntry();
                    }
                }
                main.post(()->state(false,"Exported 3-stem ZIP for Dennis Stem Lab."));
            }catch(Throwable error){failure("ZIP export failed",error);}
        });
    }
    private void exportFolder(Uri tree){
        if(busy||stems==null)return;
        String[] paths=stems.clone();
        state(true,"Exporting 3 WAV stems…");
        worker.execute(()->{
            try{
                ContentResolver resolver=getContentResolver();
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
                for(int i=0;i<3;i++){
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
                main.post(()->state(false,"Exported 3 aligned 48 kHz / 24-bit WAV stems."));
            }catch(Throwable error){failure("WAV export failed",error);}
        });
    }
    @Override protected void onDestroy(){
        stopPlayer();worker.shutdown();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }
}
