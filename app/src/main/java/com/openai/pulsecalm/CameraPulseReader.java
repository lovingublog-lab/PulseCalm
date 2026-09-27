package com.openai.pulsecalm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraPulseReader {
    public interface Listener {
        void onSignal(long timeMs, double value, double quality);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;

    public CameraPulseReader(Context context, Listener listener) { this.context=context; this.listener=listener; }

    @SuppressLint("MissingPermission")
    public void start() {
        stop();
        thread = new HandlerThread("PulseCamera"); thread.start(); handler = new Handler(thread.getLooper());
        CameraManager manager=(CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String selected=null;
            for(String id:manager.getCameraIdList()) {
                CameraCharacteristics c=manager.getCameraCharacteristics(id);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                Boolean flash=c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing!=null && facing==CameraCharacteristics.LENS_FACING_BACK && Boolean.TRUE.equals(flash)) { selected=id; break; }
            }
            if (selected==null) { listener.onError("플래시가 있는 후면 카메라를 찾지 못했습니다."); return; }
            reader=ImageReader.newInstance(320,240, ImageFormat.YUV_420_888,2);
            reader.setOnImageAvailableListener(r -> {
                Image image=null;
                try {
                    image=r.acquireLatestImage(); if(image==null) return;
                    Image.Plane yP=image.getPlanes()[0], crP=image.getPlanes()[2];
                    double y=samplePlane(yP,10); double cr=samplePlane(crP,6);
                    double quality = Math.max(0, Math.min(1, (y-35)/90.0));
                    listener.onSignal(System.currentTimeMillis(), cr, quality);
                } catch(Exception e) { listener.onError("카메라 프레임 처리 오류"); }
                finally { if(image!=null) image.close(); }
            },handler);
            manager.openCamera(selected,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){ camera=c; createSession(); }
                @Override public void onDisconnected(CameraDevice c){ c.close(); listener.onError("카메라 연결이 끊겼습니다."); }
                @Override public void onError(CameraDevice c,int error){ c.close(); listener.onError("카메라를 열 수 없습니다."); }
            },handler);
        } catch(Exception e) { listener.onError("카메라 초기화 실패: "+e.getMessage()); }
    }

    private void createSession() {
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session=s;
                    try {
                        CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        b.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH);
                        b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
                        session.setRepeatingRequest(b.build(),null,handler);
                    } catch(CameraAccessException e){ listener.onError("측정을 시작하지 못했습니다."); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s){ listener.onError("카메라 측정 세션 생성 실패"); }
            },handler);
        } catch(CameraAccessException e){ listener.onError("카메라 세션 오류"); }
    }

    private double samplePlane(Image.Plane p,int strideStep) {
        ByteBuffer b=p.getBuffer(); int row=p.getRowStride(); int pix=p.getPixelStride();
        int rows=Math.max(1,b.remaining()/Math.max(1,row)); int cols=Math.max(1,row/Math.max(1,pix));
        long sum=0; int n=0;
        for(int yy=2;yy<rows-2;yy+=strideStep) {
            for(int xx=2;xx<cols-2;xx+=strideStep) {
                int idx=yy*row+xx*pix; if(idx>=0 && idx<b.limit()){ sum += (b.get(idx)&0xFF); n++; }
            }
        }
        return n==0?0:(double)sum/n;
    }

    public void stop() {
        try { if(session!=null) session.close(); } catch(Exception ignored){} session=null;
        try { if(camera!=null) camera.close(); } catch(Exception ignored){} camera=null;
        try { if(reader!=null) reader.close(); } catch(Exception ignored){} reader=null;
        if(thread!=null){ thread.quitSafely(); thread=null; } handler=null;
    }
}
