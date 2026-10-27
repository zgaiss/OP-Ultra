package com.heavendoor.ultralab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import rikka.shizuku.Shizuku;

/** Center-display controls; the instrument itself does not require touch. */
public final class MainActivity extends Activity {
 private static final int BACKGROUND=0xff0d1519, SURFACE=0xff19262c, TEXT=0xffe5ecef, MUTED=0xff9eafb7, ACCENT=0xff91c8b6;
 private final Handler main=new Handler(Looper.getMainLooper());
 private UltraEngine engine;private LinearLayout root;private TextView status;private final Button[] cards=new Button[4];
 private String lastStatus="";private int lastCard=-1;
 private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{if(code==31&&result==PackageManager.PERMISSION_GRANTED)UltraService.launch(this);};
 @Override public void onCreate(Bundle b){
  super.onCreate(b);getWindow().setStatusBarColor(BACKGROUND);getWindow().setNavigationBarColor(BACKGROUND);engine=UltraEngine.get(this);
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BACKGROUND);
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(24,12,18,8);root.addView(header,new LinearLayout.LayoutParams(-1,100));
  LinearLayout title=new LinearLayout(this);title.setOrientation(LinearLayout.VERTICAL);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  TextView name=new TextView(this);name.setText("OP Ultra");name.setTextSize(27);name.setTextColor(TEXT);name.setTypeface(null,Typeface.BOLD);title.addView(name);
  TextView hint=new TextView(this);hint.setText("仪表显示 · 中控选择信息卡");hint.setTextColor(MUTED);hint.setTextSize(15);title.addView(hint);
  action(header,"仪表设置",()->openSettings("instrument"));action(header,"CarPlay 设置",()->openSettings("connection"));
  action(header,"CarPlay",()->{UltraService.launch(this);startActivity(new Intent(this,com.shilapi.xcertplay.OPCarPlayActivity.class));});action(header,"更多",this::more);
  status=new TextView(this);status.setTextColor(ACCENT);status.setTextSize(17);status.setPadding(26,4,26,12);root.addView(status);
  LinearLayout picker=new LinearLayout(this);picker.setPadding(18,0,18,16);root.addView(picker);
  for(int i:UltraPreferences.CARD_ORDER){final int id=i;Button tab=new Button(this);tab.setText(UltraPreferences.CARDS[i]);tab.setTextSize(21);tab.setAllCaps(false);tab.setContentDescription("切换仪表卡片："+UltraPreferences.CARDS[i]);cards[i]=tab;
   StateListDrawable bg=new StateListDrawable();bg.addState(new int[]{android.R.attr.state_selected},shape(ACCENT,16));bg.addState(new int[]{},shape(SURFACE,16));tab.setBackground(bg);tab.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected},{}},new int[]{BACKGROUND,MUTED}));
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,76,1);lp.setMargins(6,0,6,0);picker.addView(tab,lp);tab.setOnClickListener(v->engine.dashboard.selectCard(id));
  }
  if(engine.dashboard.getParent()!=null)((ViewGroup)engine.dashboard.getParent()).removeView(engine.dashboard);root.addView(engine.dashboard,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);Shizuku.addRequestPermissionResultListener(permission);
 }
 private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setCornerRadius(radius);d.setColor(color);return d;}
 private void action(LinearLayout row,String text,Runnable run){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(18);b.setTextColor(TEXT);b.setBackground(shape(SURFACE,14));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(214,68);lp.setMargins(10,0,0,0);row.addView(b,lp);b.setOnClickListener(v->run.run());}
 private AlertDialog.Builder dialog(String title){TextView t=new TextView(this);t.setText(title);t.setTextColor(TEXT);t.setTextSize(23);t.setPadding(30,28,30,18);return new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setCustomTitle(t);}
 private void finishDialog(AlertDialog d){d.show();if(d.getWindow()!=null)d.getWindow().setBackgroundDrawable(shape(SURFACE,20));if(d.getButton(-1)!=null)d.getButton(-1).setTextColor(ACCENT);if(d.getButton(-2)!=null)d.getButton(-2).setTextColor(MUTED);}
 private void openSettings(String category){startActivity(new Intent(this,com.shilapi.xcertplay.OPCarPlayActivity.class).putExtra("op.ultra.settings",true).putExtra("op.settings.category",category));}
 private void more(){finishDialog(dialog("数据与应用").setItems(new String[]{"授权并读取真实车况","停止读取车况","退出实验"},(d,n)->{if(n==0)authorize();else if(n==1){UltraPreferences.sessionEnabled(this,false);engine.stop();}else{UltraService.halt(this);com.shilapi.xcertplay.UltraHostHooks.exit();finishAndRemoveTask();}}).setNegativeButton("关闭",null).create());}
 private void authorize(){try{if(!Shizuku.pingBinder()){lastStatus="";status.setText("请先启动 Shizuku");return;}if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)UltraService.launch(this);else Shizuku.requestPermission(31);}catch(Throwable e){status.setText("Shizuku 暂不可用");}}
 private final Runnable update=new Runnable(){public void run(){String text=engine.status+" · 卡片切换立即生效";if(!text.equals(lastStatus)){lastStatus=text;status.setText(text);}int current=engine.dashboard.currentCard();if(lastCard!=current){lastCard=current;for(int i=0;i<cards.length;i++){if(cards[i]!=null)cards[i].setSelected(i==current);};}main.postDelayed(this,500);}};
 @Override protected void onResume(){super.onResume();main.removeCallbacks(update);main.post(update);}
 @Override protected void onPause(){main.removeCallbacks(update);super.onPause();}
 @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permission);main.removeCallbacksAndMessages(null);root.removeView(engine.dashboard);super.onDestroy();}
}
