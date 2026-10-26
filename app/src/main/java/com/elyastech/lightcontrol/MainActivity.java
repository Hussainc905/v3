package com.elyastech.lightcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** V1.1: maroon/black pages, onboarding gate, persistent SPP connection service. */
public final class MainActivity extends Activity implements BluetoothService.ViewListener {
    private static final String SUPPORT = "07722523232";
    private static final String PREFS = "elyas_settings";
    private static final int ASK_BT = 39;
    private static final int DARK=0xFF08070B, SURFACE=0xFF1A1019, SURFACE_2=0xFF271520;
    private static final int MAROON=0xFF963052, LIGHT=0xFFD35B7F, EDGE=0xFF683044;
    private static final int WHITE=0xFFFFF3F7, MUTED=0xFFBFAAB1, GOLD=0xFFF1B988;
    private static final int RED=0xFFC44457;
    private static final int[] SPEEDS={100,150,200,300,450,600,800,1000,1400,2000,3000};

    private BluetoothService service;
    private boolean bound=false, authorized=false, connected=false, draggingSpeed=false;
    private ProtocolState snapshot=new ProtocolState();
    private SharedPreferences prefs;
    private LinearLayout mainPanel, effectsPanel, settingsPanel;
    private TextView navMain,navEffects,navSettings, linkButton, linkValue,receiverValue, speedLabel, status;
    private TextView skin, led, pair1,pair2,pair3,four,six,flash,wave,auto,manual,allOff,strongOff;
    private SeekBar speedBar;
    private final List<TextView> buttons = new ArrayList<>();
    private final ServiceConnection link=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName component, IBinder ib){
            service=((BluetoothService.LocalBinder)ib).getService();bound=true;
            service.setViewListener(MainActivity.this);
            if(prefs.getBoolean("remember_link",false) && prefs.getBoolean("access_unlocked",false)
                && checkBluetoothPermission()) startSavedConnection();
            render();
        }
        @Override public void onServiceDisconnected(ComponentName component){service=null;bound=false;render();}
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        buildUi();render();
        bindService(new Intent(this,BluetoothService.class),link,Context.BIND_AUTO_CREATE);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);
        if(!prefs.getBoolean("access_unlocked",false)) promptEntry(false);
        else if(prefs.getBoolean("lock_enabled",false)) promptEntry(true);
    }
    @Override public void onDestroy(){
        if(bound){service.setViewListener(null);unbindService(link);bound=false;}
        // Do NOT disconnect here: foreground connection service owns the socket.
        super.onDestroy();
    }

    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    private GradientDrawable gradient(int top,int bottom,int stroke,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});
        d.setCornerRadius(dp(radius)); if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private LinearLayout col(){LinearLayout ll=new LinearLayout(this);ll.setOrientation(1);ll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return ll;}
    private LinearLayout row(){LinearLayout ll=new LinearLayout(this);ll.setOrientation(0);ll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return ll;}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private TextView tile(String label){
        TextView t=text(label,15,WHITE,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(5),dp(7),dp(5),dp(7));
        t.setBackground(gradient(0xFF3B1A2B,0xFF160D16,EDGE,17));t.setElevation(dp(5));
        t.setClickable(true);t.setFocusable(true);t.setContentDescription(label);buttons.add(t);return t;
    }
    private void configure(TextView btn,int bits,String command){btn.setOnClickListener(v->send(command+(ProtocolState.enabled(snapshot.requestedBits,bits)?" OFF":" ON")));}
    private void align(LinearLayout target,View... children){
        for(int i=0;i<children.length;i++){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1f);
            if(i<children.length-1)p.setMarginEnd(dp(8));target.addView(children[i],p);
        }
    }
    private void textTitle(LinearLayout ll,String title){TextView t=text(title,15,0xFFFFDAE5,true);LinearLayout.LayoutParams p=size(-1,24);p.topMargin=dp(9);ll.addView(t,p);}

    private void buildUi(){
        LinearLayout root=col();root.setBackgroundColor(DARK);
        root.setPadding(dp(12),dp(14),dp(12),dp(8));
        root.setFitsSystemWindows(true); // keep title below status-bar icons on Android 15+
        setContentView(root);

        // Brand banner: replacement-ready logo asset supplied in res/drawable/brand_logo.png.
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8),dp(7),dp(8),dp(7));head.setBackground(gradient(0xFF421B2C,0xFF140C15,EDGE,19));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.brand_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);head.addView(logo,size(76,58));
        LinearLayout names=col();names.setPadding(dp(9),0,dp(7),0);
        names.addView(text("ELYAS-TECH",19,WHITE,true));
        names.addView(text("LIGHT CONTROL  •  V1.2",11,GOLD,true));
        TextView phone=text("الدعم  " + SUPPORT,12,0xFFFFD9E3,true);
        phone.setOnClickListener(v->dial());names.addView(phone,size(-1,23));
        head.addView(names,new LinearLayout.LayoutParams(0,-2,1));root.addView(head,size(-1,75));

        LinearLayout connection=row();connection.setGravity(Gravity.CENTER_VERTICAL);
        connection.setPadding(dp(10),dp(5),dp(10),dp(5));connection.setBackground(gradient(0xFF23131D,0xFF120C12,EDGE,13));
        linkButton=text("ربط",13,WHITE,true);linkButton.setGravity(Gravity.CENTER);
        linkButton.setBackground(gradient(MAROON,0xFF5D1730,0,11));
        linkButton.setElevation(dp(3));linkButton.setOnClickListener(v->onConnectClick());
        connection.addView(linkButton,size(70,39));
        LinearLayout statusGroup=col();statusGroup.setPadding(dp(10),0,dp(5),0);
        linkValue=text("غير متصل",14,GOLD,true);
        receiverValue=text("بانتظار الوحدة",10,MUTED,false);
        statusGroup.addView(linkValue);statusGroup.addView(receiverValue);
        connection.addView(statusGroup,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams cp=size(-1,57);cp.topMargin=dp(8);root.addView(connection,cp);

        LinearLayout tabs=row();tabs.setGravity(Gravity.CENTER);
        navMain=tab("الإنارة");navEffects=tab("الحركات");navSettings=tab("الإعدادات");
        align(tabs,navMain,navEffects,navSettings);
        navMain.setOnClickListener(v->select(0));navEffects.setOnClickListener(v->select(1));navSettings.setOnClickListener(v->select(2));
        LinearLayout.LayoutParams tp=size(-1,47);tp.topMargin=dp(9);root.addView(tabs,tp);

        FrameLayout pane=new FrameLayout(this);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,0,1);
        pp.topMargin=dp(4);root.addView(pane,pp);
        mainPanel=col();effectsPanel=col();settingsPanel=col();
        pane.addView(mainPanel,new FrameLayout.LayoutParams(-1,-1));
        pane.addView(effectsPanel,new FrameLayout.LayoutParams(-1,-1));
        pane.addView(settingsPanel,new FrameLayout.LayoutParams(-1,-1));
        buildMain();buildEffects();buildSettings();select(0);
        status=text("التحكم من الهاتف بدون إنترنت",10,MUTED,false);
        status.setMaxLines(1);status.setGravity(Gravity.CENTER);root.addView(status,size(-1,21));
    }
    private TextView tab(String name){TextView b=text(name,13,WHITE,true);b.setGravity(Gravity.CENTER);b.setBackground(gradient(SURFACE_2,SURFACE,EDGE,12));return b;}
    private void select(int page){
        mainPanel.setVisibility(page==0?View.VISIBLE:View.GONE);
        effectsPanel.setVisibility(page==1?View.VISIBLE:View.GONE);
        settingsPanel.setVisibility(page==2?View.VISIBLE:View.GONE);
        TextView[] n={navMain,navEffects,navSettings};
        for(int i=0;i<n.length;i++)n[i].setBackground(i==page?gradient(0xFFA93B5D,0xFF551A32,0xFFD77A92,13):gradient(SURFACE_2,SURFACE,EDGE,13));
    }
    private void rowTiles(LinearLayout panel,int height, TextView... elements){
        LinearLayout r=row();r.setPadding(dp(3),dp(5),dp(3),dp(3));align(r,elements);
        panel.addView(r,size(-1,height));
    }
    private void buildMain(){
        textTitle(mainPanel,"الإضاءة الرئيسية");
        skin=tile("◉   السكن");configure(skin,ProtocolState.SKIN,"SKIN");
        led=tile("LED  ●");configure(led,ProtocolState.LED,"LED");
        rowTiles(mainPanel,69,skin,led);
        textTitle(mainPanel,"البروجكترات القوية");
        pair1=tile("1 + 2");pair2=tile("3 + 4");pair3=tile("5 + 6");
        configure(pair1,ProtocolState.PAIR1,"PAIR1");configure(pair2,ProtocolState.PAIR2,"PAIR2");configure(pair3,ProtocolState.PAIR3,"PAIR3");
        rowTiles(mainPanel,70,pair1,pair2,pair3);
        four=tile("تشغيل 4");six=tile("تشغيل 6");
        configure(four,ProtocolState.FOUR,"FOUR");configure(six,ProtocolState.SIX,"SIX");
        rowTiles(mainPanel,69,four,six);
        textTitle(mainPanel,"إيقاف");
        strongOff=tile("إطفاء القوي");allOff=tile("⏻  إطفاء الجميع");
        strongOff.setOnClickListener(v->send("STRONG OFF"));allOff.setOnClickListener(v->send("ALL OFF"));
        rowTiles(mainPanel,68,strongOff,allOff);
        ImageView truck=new ImageView(this);
        truck.setImageResource(R.drawable.dodge_ram);
        truck.setContentDescription("Dodge Ram");
        truck.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams photo=size(-1,105);
        photo.topMargin=dp(8);
        mainPanel.addView(truck,photo);
    }
    private void buildEffects(){
        textTitle(effectsPanel,"حركات الإضاءة");
        flash=tile("ϟ  رماش سريع");wave=tile("↔  موجة الأزواج");
        flash.setOnClickListener(v->mode(1,"FLASH"));wave.setOnClickListener(v->mode(2,"WAVE"));
        rowTiles(effectsPanel,85,flash,wave);
        auto=tile("✦  العرض التلقائي");manual=tile("■  إيقاف الحركات");
        auto.setOnClickListener(v->mode(3,"AUTO"));manual.setOnClickListener(v->send("MODE MANUAL"));
        rowTiles(effectsPanel,85,auto,manual);
        textTitle(effectsPanel,"سرعة الحركة");
        LinearLayout speedBox=col();speedBox.setPadding(dp(14),dp(10),dp(14),dp(6));
        speedBox.setBackground(gradient(0xFF321623,0xFF180D17,EDGE,15));
        speedLabel=text("200 ms",17,GOLD,true);speedLabel.setGravity(Gravity.CENTER);
        speedBox.addView(speedLabel,size(-1,25));
        speedBar=new SeekBar(this);speedBar.setMax(SPEEDS.length-1);speedBar.setProgress(2);
        speedBar.setProgressTintList(ColorStateList.valueOf(LIGHT));speedBar.setThumbTintList(ColorStateList.valueOf(LIGHT));
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int pos,boolean user){if(user||draggingSpeed)speedLabel.setText(SPEEDS[pos]+" ms");}
            @Override public void onStartTrackingTouch(SeekBar b){draggingSpeed=true;}
            @Override public void onStopTrackingTouch(SeekBar b){draggingSpeed=false;send("SPEED " + SPEEDS[b.getProgress()]);}
        });
        speedBox.addView(speedBar,size(-1,45));
        TextView hint=text("الأسرع 100 ms                 الأبطأ 3000 ms",11,MUTED,false);
        hint.setGravity(Gravity.CENTER);speedBox.addView(hint,size(-1,25));
        LinearLayout.LayoutParams sp=size(-1,105);sp.topMargin=dp(6);effectsPanel.addView(speedBox,sp);
        TextView warn=text("تنبيه: الرماش السريع يستهلك عمر الريليات الميكانيكية.",11,GOLD,false);
        warn.setGravity(Gravity.CENTER);LinearLayout.LayoutParams wp=size(-1,38);wp.topMargin=dp(8);effectsPanel.addView(warn,wp);
    }
    private void buildSettings(){
        ScrollView sv=new ScrollView(this);sv.setVerticalScrollBarEnabled(false);
        LinearLayout content=col();content.setPadding(dp(5),dp(8),dp(5),dp(12));sv.addView(content);settingsPanel.addView(sv,size(-1,-1));
        settingsRow(content,"طريقة الاستخدام","شرح الأزرار والبلوتوث",()->help());
        settingsRow(content,"الاتصال بالخلفية","يبقى البلوتوث يعمل بعد مغادرة التطبيق",()->backgroundHelp());
        settingsRow(content,"رمز قفل التطبيق","يمكن طلبه كل مرة تفتح التطبيق",()->configureLock());
        settingsRow(content,"تغيير كلمة المرور","تعديل رمز قفل التطبيق",()->changePin());
        settingsRow(content,"إعدادات الاقتران المتقدمة","رمز اقتران وحدة الإضاءة",()->editUnitPin());
        settingsRow(content,"الاتصال بالدعم","اتصل على " + SUPPORT,()->dial());
        settingsRow(content,"فصل الاتصال","إيقاف خدمة البلوتوث يدوياً",()->stopLink());
        TextView foot=text("ELYAS-TECH  •  V1.2\n"+ SUPPORT,12,GOLD,true);
        foot.setGravity(Gravity.CENTER);LinearLayout.LayoutParams fp=size(-1,60);fp.topMargin=dp(11);content.addView(foot,fp);
    }
    private void settingsRow(LinearLayout p,String title,String desc,Runnable action){
        LinearLayout card=col();card.setPadding(dp(15),dp(10),dp(15),dp(10));
        card.setBackground(gradient(0xFF351624,0xFF130C13,EDGE,14));card.setElevation(dp(3));
        card.addView(text(title,15,WHITE,true));card.addView(text(desc,11,MUTED,false));
        card.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=size(-1,66);lp.topMargin=dp(8);p.addView(card,lp);
    }
    private void help(){new AlertDialog.Builder(this).setTitle("تعليمات التشغيل")
        .setMessage("١. شغّل البلوتوث واقترن مع ELYAS-LIGHT من إعدادات الهاتف.\n\n"+
        "٢. اضغط ربط واختر شاشة الإضاءة مرة واحدة؛ الجهاز يُحفظ ويتصل تلقائياً لاحقاً.\n\n"+
        "٣. صفحة الإنارة: السكن وLED الأحمر زران مستقلان؛ أزرار 1+2 و3+4 و5+6 تتحكم بأزواج البروجكترات.\n\n"+
        "٤. صفحة الحركات: الرماش والموجة والعرض التلقائي. اضغط الحركة نفسها مرة ثانية لإيقافها.\n\n"+
        "٥. صفحة الإعدادات: التحكم بالقفل والدعم والفصل اليدوي.\n\n"+
        "٦. إذا ابتعدت عن الشاشة، سيحاول التطبيق إعادة الاتصال عند رجوعك ضمن المدى.")
        .setPositiveButton("فهمت",null).show();}
    private void backgroundHelp(){new AlertDialog.Builder(this).setTitle("الاتصال المستمر")
        .setMessage("بعد ربط الجهاز تبقى خدمة البلوتوث بالخلفية مع إشعار يمكن من خلاله فصل الاتصال. إغلاق واجهة التطبيق لا يفصل الاتصال عادةً.\n\n"+
        "إذا أوقف النظام الخدمة لتوفير البطارية أو أجريت إيقافاً إجبارياً للتطبيق، فقد ينقطع الاتصال. عند فتح التطبيق يحاول الاتصال مجدداً. لإيقافه يدوياً استخدم فصل الاتصال.")
        .setPositiveButton("موافق",null).show();}
    private void dial(){startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+SUPPORT)));}

    private String hash(String code){
        try{MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] bytes=sha.digest(("elyas.local.v11:"+code).getBytes("UTF-8"));
            StringBuilder sb=new StringBuilder();for(byte b:bytes)sb.append(String.format(Locale.US,"%02x",b&0xFF));return sb.toString();
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private String savedHash(){return prefs.getString("local_pin_hash",hash("223232"));}
    private void promptEntry(boolean repeat){
        EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setHint("رمز التطبيق");LinearLayout p=col();p.setPadding(dp(25),dp(9),dp(25),0);p.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("ELYAS-TECH  |  دخول")
            .setMessage(repeat?"أدخل رمز قفل التطبيق":"أدخل رمز التفعيل عند التشغيل الأول. هذا الطلب يظهر مرة واحدة بعد تثبيت التطبيق.")
            .setView(p).setCancelable(false).setNegativeButton("إغلاق",(d,w)->finish())
            .setPositiveButton("دخول",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            if(hash(field.getText().toString().trim()).equals(savedHash())){
                prefs.edit().putBoolean("access_unlocked",true).apply();dialog.dismiss();
                if(prefs.getBoolean("remember_link",false) && checkBluetoothPermission())startSavedConnection();
            }else{field.setError("رمز غير صحيح");field.setText("");}
        }));dialog.show();
    }
    private void configureLock(){
        boolean enabled=prefs.getBoolean("lock_enabled",false);
        new AlertDialog.Builder(this).setTitle("قفل التطبيق")
        .setMessage(enabled?"القفل يعمل حالياً. هل تريد تعطيله؟":"هل تريد طلب رمز التطبيق عند كل فتح؟")
        .setNegativeButton("إلغاء",null)
        .setPositiveButton(enabled?"تعطيل":"تفعيل",(d,w)->{
            prefs.edit().putBoolean("lock_enabled",!enabled).apply();toast(!enabled?"تم تفعيل القفل":"تم تعطيل القفل");
        }).show();
    }
    private void changePin(){
        LinearLayout layout=col();layout.setPadding(dp(24),0,dp(24),0);
        EditText old=new EditText(this);old.setHint("الرمز الحالي");old.setInputType(18);
        EditText next=new EditText(this);next.setHint("رمز جديد 6 أرقام");next.setInputType(18);
        layout.addView(old);layout.addView(next);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("تغيير رمز التطبيق").setView(layout).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{
            if(!hash(old.getText().toString()).equals(savedHash())){old.setError("الرمز الحالي غير صحيح");return;}
            String newCode=next.getText().toString().trim();if(!newCode.matches("[0-9]{6}")){next.setError("الرمز 6 أرقام");return;}
            prefs.edit().putString("local_pin_hash",hash(newCode)).apply();toast("تم تحديث الرمز");d.dismiss();
        }));d.show();
    }
    private void editUnitPin(){
        EditText code=new EditText(this);code.setSingleLine(true);code.setInputType(18);
        code.setHint("رمز اقتران الوحدة");code.setText(prefs.getString("firmware_pin","462913"));
        LinearLayout p=col();p.setPadding(dp(26),0,dp(26),0);p.addView(code);
        new AlertDialog.Builder(this).setTitle("إعدادات اقتران الشاشة")
            .setMessage("هذا ليس رمز فتح التطبيق. غيّره فقط إذا تغيّر رمز اقتران الوحدة من إعداداتها.")
            .setView(p).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",(d,w)->{
                String s=code.getText().toString().trim();if(s.matches("[0-9]{6}")){
                    prefs.edit().putString("firmware_pin",s).apply();toast("تم حفظ رمز الاقتران");
                }else toast("أدخل 6 أرقام");}).show();
    }
    private boolean checkBluetoothPermission(){return Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void startSavedConnection(){
        String address=prefs.getString("device_address","");if(address.isEmpty())return;
        Intent i=new Intent(this,BluetoothService.class).setAction(BluetoothService.START).putExtra(BluetoothService.EXTRA_ADDRESS,address);
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ex){toast("تعذر بدء الاتصال بالخلفية");}
    }
    private void stopLink(){startService(new Intent(this,BluetoothService.class).setAction(BluetoothService.STOP));render();}
    private void onConnectClick(){
        if(!prefs.getBoolean("access_unlocked",false)){promptEntry(false);return;}
        if(connected || (service!=null && service.shouldConnect())){
            new AlertDialog.Builder(this).setTitle("اتصال الإضاءة")
                .setMessage("هل تريد فصل الاتصال، أم تغيير الجهاز؟")
                .setNegativeButton("إلغاء",null)
                .setNeutralButton("تغيير الجهاز",(d,w)->showPaired())
                .setPositiveButton("فصل",(d,w)->stopLink()).show();return;
        }
        if(!checkBluetoothPermission()){
            if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},ASK_BT);
            return;
        }
        if(!prefs.getString("device_address","").isEmpty())startSavedConnection();
        else showPaired();
    }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] results){
        super.onRequestPermissionsResult(code,p,results);
        if(code==ASK_BT && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)showPaired();
    }
    @SuppressWarnings("MissingPermission") private void showPaired(){
        if(!checkBluetoothPermission()){if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},ASK_BT);return;}
        BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=m==null?null:m.getAdapter();
        if(adapter==null){toast("الهاتف لا يدعم البلوتوث");return;}
        if(!adapter.isEnabled()){
            new AlertDialog.Builder(this).setTitle("البلوتوث مطفأ")
                .setMessage("شغّله من إعدادات الهاتف، ثم ارجع إلى التطبيق.")
                .setPositiveButton("الإعدادات",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("إلغاء",null).show();return;
        }
        Set<BluetoothDevice> paired;
        try{paired=adapter.getBondedDevices();}catch(SecurityException ex){toast("اسمح بإذن الأجهزة المجاورة");return;}
        if(paired==null || paired.isEmpty()){toast("اقترن مع ELYAS-LIGHT من إعدادات البلوتوث أولاً");return;}
        ArrayList<BluetoothDevice> devices=new ArrayList<>(paired);
        Collections.sort(devices,(a,b)->{
            String an=deviceName(a),bn=deviceName(b);
            if(an.equalsIgnoreCase("ELYAS-LIGHT"))return -1;if(bn.equalsIgnoreCase("ELYAS-LIGHT"))return 1;
            return an.compareToIgnoreCase(bn);
        });
        String[] names=new String[devices.size()];for(int i=0;i<devices.size();i++)names[i]=deviceName(devices.get(i));
        new AlertDialog.Builder(this).setTitle("اختر شاشة الإضاءة")
            .setItems(names,(dialog,which)->{
                String a=devices.get(which).getAddress();
                prefs.edit().putString("device_address",a).putBoolean("remember_link",true).apply();startSavedConnection();
            }).setNegativeButton("إلغاء",null).show();
    }
    @SuppressWarnings("MissingPermission") private String deviceName(BluetoothDevice d){
        try{return d.getName()==null?d.getAddress():d.getName();}catch(SecurityException ex){return "جهاز مقترن";}
    }
    private void send(String cmd){
        if(service==null || !service.isConnected() || !service.isAuthorized()){toast("اتصل بوحدة الإضاءة أولاً");return;}
        service.send(cmd);status.setText("تم إرسال الأمر");
    }
    private void mode(int id,String code){send("MODE " + (snapshot.mode==id?"MANUAL":code));}
    @Override public void onServiceChanged(){render();}
    @Override public void onServiceMessage(String msg){status.setText(msg);toast(msg);}
    private void decorate(TextView t,boolean active,boolean danger){
        if(t==null)return;
        t.setBackground(active?gradient(0xFFBC476C,0xFF621D3C,0xFFED99AD,17):
            danger?gradient(0xFF842239,0xFF3D1023,EDGE,17):gradient(0xFF401D2D,0xFF120B13,EDGE,17));
        t.setTextColor(active?WHITE:0xFFF6E5EC);t.setAlpha(authorized?1f:0.78f);
        t.setEnabled(authorized);
    }
    private void render(){
        if(linkValue==null)return;
        connected=service!=null&&service.isConnected();authorized=service!=null&&service.isAuthorized();
        boolean linking=service!=null&&service.isConnecting();
        snapshot=service!=null?service.protocol():new ProtocolState();
        linkValue.setText(authorized?"متصل وجاهز":connected?"جاري التحقق":linking?"جاري الاتصال":
            service!=null&&service.shouldConnect()?"إعادة اتصال تلقائية":"غير متصل");
        linkValue.setTextColor(authorized?0xFF91E1B6:linking?GOLD:LIGHT);
        linkButton.setText(connected?"الربط ✓":"ربط");
        receiverValue.setText(authorized?(snapshot.ackReceivedThisConnection?"تم تأكيد الاستلام من الوحدة":"بانتظار تأكيد الوحدة"):
            service==null?"بانتظار الاتصال":service.message());
        int b=snapshot.requestedBits;
        decorate(skin,ProtocolState.enabled(b,ProtocolState.SKIN),false);
        decorate(led,ProtocolState.enabled(b,ProtocolState.LED),false);
        decorate(pair1,ProtocolState.enabled(b,ProtocolState.PAIR1),false);
        decorate(pair2,ProtocolState.enabled(b,ProtocolState.PAIR2),false);
        decorate(pair3,ProtocolState.enabled(b,ProtocolState.PAIR3),false);
        decorate(four,ProtocolState.enabled(b,ProtocolState.FOUR),false);
        decorate(six,ProtocolState.enabled(b,ProtocolState.SIX),false);
        decorate(flash,snapshot.mode==1,false);decorate(wave,snapshot.mode==2,false);
        decorate(auto,snapshot.mode==3,false);decorate(manual,snapshot.mode==0,false);
        decorate(strongOff,false,false);decorate(allOff,false,true);
        speedBar.setEnabled(authorized);
        if(snapshot.hasState&&!draggingSpeed){
            int idx=0;for(int i=0;i<SPEEDS.length;i++)if(Math.abs(SPEEDS[i]-snapshot.speedMs)<Math.abs(SPEEDS[idx]-snapshot.speedMs))idx=i;
            speedBar.setProgress(idx);speedLabel.setText(snapshot.speedMs+" ms");
        }
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
}
