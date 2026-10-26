package com.elyastech.lightcontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Locale;

/** User-initiated foreground service: SPP link remains active when UI exits. */
@SuppressWarnings("MissingPermission")
public final class BluetoothService extends Service implements BluetoothClient.Listener {
    public static final String START = "com.elyastech.lightcontrol.CONNECT";
    public static final String STOP = "com.elyastech.lightcontrol.DISCONNECT";
    public static final String EXTRA_ADDRESS = "address";
    private static final String PREFS = "elyas_settings";
    private static final String CHANNEL = "elyas_link_status";
    private static final int NOTIFICATION_ID = 2232;
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothClient client;
    private final ProtocolState state = new ProtocolState();
    private String address = "";
    private String label = "ELYAS-LIGHT";
    private String message = "غير متصل";
    private boolean foreground = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean authorized = false;
    private boolean intended = false;
    private int failures = 0;
    private ViewListener view;

    public interface ViewListener { void onServiceChanged(); void onServiceMessage(String text); }
    public final class LocalBinder extends Binder { public BluetoothService getService(){ return BluetoothService.this; } }
    public void setViewListener(ViewListener listener){ this.view=listener; if (listener!=null) handler.post(listener::onServiceChanged); }
    public boolean isConnected(){return connected;}
    public boolean isConnecting(){return connecting;}
    public boolean isAuthorized(){return authorized;}
    public boolean shouldConnect(){return intended;}
    public String message(){return message;}
    public String deviceLabel(){return label;}
    public ProtocolState protocol(){return state;}

    @Override public void onCreate(){
        super.onCreate();
        client=new BluetoothClient(this);
        if (Build.VERSION.SDK_INT>=26) {
            NotificationChannel nc=new NotificationChannel(CHANNEL,"اتصال ELYAS LIGHT",NotificationManager.IMPORTANCE_LOW);
            nc.setDescription("متابعة اتصال وحدة الإضاءة");
            getSystemService(NotificationManager.class).createNotificationChannel(nc);
        }
    }
    @Override public IBinder onBind(Intent i){return binder;}

    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null && STOP.equals(i.getAction())){
            intended=false;
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("remember_link",false).apply();
            handler.removeCallbacksAndMessages(null);
            client.disconnect();
            stopForeground(true); stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        String selected=i==null ? prefs.getString("device_address","") : i.getStringExtra(EXTRA_ADDRESS);
        if (selected==null || selected.isEmpty()) selected=prefs.getString("device_address","");
        if(selected.isEmpty() || !prefs.getBoolean("access_unlocked",false)) {stopSelf(); return START_NOT_STICKY;}
        String previous=address;
        address=selected;
        if(!previous.isEmpty() && !previous.equals(address)) client.disconnect();
        intended=true;
        prefs.edit().putString("device_address",address).putBoolean("remember_link",true).apply();
        ensureForeground();
        if(!connected && !connecting) attempt();
        return START_STICKY;
    }
    private void ensureForeground(){
        Notification n=notification();
        try {
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID,n);
            foreground=true;
        }catch(Exception ex){
            message="تعذر تشغيل الاتصال بالخلفية: " + ex.getClass().getSimpleName();
            intended=false; stopSelf();
        }
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags=PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT>=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi=PendingIntent.getActivity(this,0,open,piFlags);
        Intent stop=new Intent(this,BluetoothService.class).setAction(STOP);
        PendingIntent stopPi=PendingIntent.getService(this,1,stop,piFlags);
        Notification.Builder nb=Build.VERSION.SDK_INT>=26 ? new Notification.Builder(this,CHANNEL) : new Notification.Builder(this);
        nb.setSmallIcon(R.drawable.ic_notification)
          .setContentTitle("ELYAS LIGHT CONTROL")
          .setContentText(connected ? "متصل بوحدة الإضاءة" : connecting ? "جاري الاتصال بوحدة الإضاءة" : "جاري انتظار إعادة الاتصال")
          .setContentIntent(openPi)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .addAction(new Notification.Action.Builder(R.drawable.ic_notification,"فصل الاتصال",stopPi).build());
        return nb.build();
    }
    private void updateNotification(){
        if(!foreground)return;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }
    private void attempt(){
        if(!intended || address.isEmpty() || connected || connecting)return;
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            message="اسمح بإذن الأجهزة المجاورة"; notifyView(); return;
        }
        try {
            BluetoothManager bm=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=bm==null ? null : bm.getAdapter();
            if(adapter==null || !adapter.isEnabled()) {message="فعّل Bluetooth لإعادة الاتصال";notifyView();retry();return;}
            BluetoothDevice device=adapter.getRemoteDevice(address);
            connecting=true; message="جاري الاتصال...";notifyView();updateNotification();
            client.connect(device,getSharedPreferences(PREFS,MODE_PRIVATE).getString("firmware_pin","462913"));
        }catch(Exception ex){ connecting=false;message="تعذر الاتصال";notifyView();retry(); }
    }
    private void retry(){
        if(!intended)return;
        handler.removeCallbacks(retryTask);
        int delay=Math.min(30000,3500+failures*2500);
        failures=Math.min(10,failures+1);
        handler.postDelayed(retryTask,delay);
    }
    private final Runnable retryTask=()->attempt();
    public void send(String command){ if(connected && authorized) client.send(command); }
    public void requestStatus(){if(connected && authorized) client.send("STATUS");}
    private void notifyView(){ if(view!=null) handler.post(()->{ if(view!=null)view.onServiceChanged(); }); }
    private void notifyMessage(String s){ if(view!=null) handler.post(()->{if(view!=null)view.onServiceMessage(s);}); }
    @Override public void onConnecting(String name){ label=name;connecting=true;connected=false;authorized=false;message="جاري الاتصال";notifyView();updateNotification(); }
    @Override public void onConnected(String name){label=name; connecting=false;connected=true;failures=0;state.resetForNewConnection();message="متصل — جاري التحقق";notifyView();updateNotification();}
    @Override public void onDisconnected(String reason){
        connected=false;connecting=false;authorized=false;
        message=intended ? "منقطع — إعادة اتصال تلقائية" : "غير متصل";
        notifyView();updateNotification();
        if(intended)retry();
    }
    @Override public void onLine(String line){
        if(line.startsWith("OK AUTH")){ authorized=true; message="متصل وجاهز"; client.send("STATUS"); }
        else if(line.startsWith("ERR AUTH") || line.startsWith("ERR UNAUTHORIZED")) {
            authorized=false;message="رمز اقتران وحدة الإضاءة غير مطابق";notifyMessage(message);client.disconnect();
            // Don't hammer authentication attempts; user may update the code in settings.
            intended=false;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("remember_link",false).apply();
        } else if(line.startsWith("ERR ")) {notifyMessage("الوحدة: " + line);}
        else if (line.startsWith("OK")) message="تم استلام الأمر";
        state.consume(line);
        notifyView();
    }
    @Override public void onDestroy(){
        intended=false;handler.removeCallbacksAndMessages(null);
        if(client!=null)client.disconnect();
        foreground=false;
        super.onDestroy();
    }
}
