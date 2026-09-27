package com.openai.pulsecalm;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements CameraPulseReader.Listener {
    private static final int REQ_CAMERA=42;
    private static final long DURATION_MS=45_000L;

    private PulseView pulseView;
    private TextView statusText,bpmText,hrvText,stressText,stressHint;
    private ProgressBar progressBar;
    private Button startButton;
    private LinearLayout historyContainer;
    private CameraPulseReader cameraReader;
    private final PpgAnalyzer analyzer=new PpgAnalyzer();
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean measuring=false;
    private long startedAt=0;
    private PpgAnalyzer.Result latest;
    private double qualityEma=0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pulseView=findViewById(R.id.pulseView);
        statusText=findViewById(R.id.statusText);
        bpmText=findViewById(R.id.bpmText);
        hrvText=findViewById(R.id.hrvText);
        stressText=findViewById(R.id.stressText);
        stressHint=findViewById(R.id.stressHint);
        progressBar=findViewById(R.id.progressBar);
        startButton=findViewById(R.id.startButton);
        historyContainer=findViewById(R.id.historyContainer);
        cameraReader=new CameraPulseReader(this,this);
        startButton.setOnClickListener(v -> { if(measuring) stopMeasurement(false); else ensurePermissionAndStart(); });
        renderHistory();
    }

    private void ensurePermissionAndStart() {
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startMeasurement();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_CAMERA && grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) startMeasurement();
        else Toast.makeText(this,"심박 측정을 위해 카메라 권한이 필요합니다.",Toast.LENGTH_LONG).show();
    }

    private void startMeasurement() {
        analyzer.reset(); latest=null; qualityEma=0; startedAt=System.currentTimeMillis(); measuring=true;
        bpmText.setText("--"); hrvText.setText("--"); stressText.setText("-- / 100");
        stressHint.setText("편하게 앉아 손가락을 움직이지 마세요.");
        progressBar.setProgress(0); statusText.setText("손가락 감지 중");
        startButton.setText("측정 중지"); pulseView.setBpm(0); pulseView.setActive(true);
        cameraReader.start();
        main.post(tick);
    }

    private final Runnable tick=new Runnable(){ @Override public void run(){
        if(!measuring) return;
        long elapsed=System.currentTimeMillis()-startedAt;
        progressBar.setProgress((int)Math.min(45,elapsed/1000));
        if(elapsed>=DURATION_MS) stopMeasurement(true); else main.postDelayed(this,250);
    }};

    @Override public void onSignal(long timeMs,double value,double quality) {
        if(!measuring) return;
        qualityEma = qualityEma==0 ? quality : qualityEma*0.92+quality*0.08;
        if(qualityEma<0.12) {
            main.post(() -> statusText.setText("손가락을 카메라·플래시에 대세요"));
            return;
        }
        PpgAnalyzer.Result r=analyzer.add(timeMs,value); latest=r;
        main.post(() -> {
            statusText.setText(r.reliable ? "신호 양호 · 측정 중" : "맥박 신호를 찾는 중");
            if(r.bpm>0){ bpmText.setText(String.valueOf(r.bpm)); pulseView.setBpm(r.bpm); }
            if(r.rmssd>0) hrvText.setText(String.valueOf((int)Math.round(r.rmssd)));
            if(r.stress>=0){ stressText.setText(r.stress+" / 100"); stressHint.setText(stressLabel(r.stress)); }
        });
    }

    @Override public void onError(String message) {
        main.post(() -> {
            if(measuring) stopMeasurement(false);
            Toast.makeText(this,message,Toast.LENGTH_LONG).show();
        });
    }

    private void stopMeasurement(boolean completed) {
        if(!measuring) return;
        measuring=false; main.removeCallbacks(tick); cameraReader.stop(); pulseView.setActive(false);
        startButton.setText("측정 시작"); progressBar.setProgress(completed?45:0);
        if(completed && latest!=null && latest.reliable && latest.bpm>=40 && latest.bpm<=200 && latest.rmssd>0) {
            statusText.setText("측정 완료");
            saveResult(latest);
            renderHistory();
        } else if(completed) {
            statusText.setText("신호가 부족합니다");
            stressHint.setText("손가락을 카메라와 플래시에 완전히 대고 다시 측정해보세요.");
        } else {
            statusText.setText("측정 중지됨");
        }
    }

    private String stressLabel(int score) {
        if(score<35) return "현재 생체신호 기준으로 비교적 안정적인 편입니다.";
        if(score<65) return "현재 생체신호 기준으로 중간 범위입니다.";
        return "현재 생체신호 기준으로 긴장도가 높게 추정됩니다. 휴식 후 다시 측정해보세요.";
    }

    private void saveResult(PpgAnalyzer.Result r) {
        SharedPreferences p=getSharedPreferences("history",Context.MODE_PRIVATE);
        try {
            JSONArray old=new JSONArray(p.getString("items","[]"));
            JSONArray arr=new JSONArray();
            JSONObject now=new JSONObject();
            now.put("time",System.currentTimeMillis()); now.put("bpm",r.bpm);
            now.put("hrv",Math.round(r.rmssd)); now.put("stress",r.stress); arr.put(now);
            for(int i=0;i<Math.min(19,old.length());i++) arr.put(old.getJSONObject(i));
            p.edit().putString("items",arr.toString()).apply();
        } catch(Exception ignored){}
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        try {
            JSONArray arr=new JSONArray(getSharedPreferences("history",Context.MODE_PRIVATE).getString("items","[]"));
            if(arr.length()==0){ TextView empty=smallText("아직 저장된 측정이 없습니다."); historyContainer.addView(empty); return; }
            SimpleDateFormat fmt=new SimpleDateFormat("M월 d일  HH:mm",Locale.KOREA);
            for(int i=0;i<Math.min(6,arr.length());i++) {
                JSONObject o=arr.getJSONObject(i);
                LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0,dp(11),0,dp(11));
                TextView when=smallText(fmt.format(new Date(o.getLong("time"))));
                when.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
                TextView value=smallText(o.getInt("bpm")+" bpm   ·   "+o.getLong("hrv")+" ms   ·   스트레스 "+o.getInt("stress"));
                value.setTextColor(Color.rgb(17,24,39)); value.setGravity(Gravity.END);
                row.addView(when); row.addView(value); historyContainer.addView(row);
                if(i<Math.min(6,arr.length())-1){ View line=new View(this); line.setBackgroundColor(Color.rgb(229,231,235)); historyContainer.addView(line,new LinearLayout.LayoutParams(-1,dp(1))); }
            }
        } catch(Exception e){ historyContainer.addView(smallText("기록을 불러오지 못했습니다.")); }
    }

    private TextView smallText(String s){ TextView t=new TextView(this); t.setText(s); t.setTextSize(13); t.setTextColor(Color.rgb(102,112,133)); return t; }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override protected void onPause(){ super.onPause(); if(measuring) stopMeasurement(false); }
    @Override protected void onDestroy(){ cameraReader.stop(); super.onDestroy(); }
}
