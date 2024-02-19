package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
  public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

  private static final String TAG = "OverlayService";

  private static final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
  private static final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

  private Integer mStatusBarHeight = -1;
  private Integer mNavigationBarHeight = -1;
  private Resources mResources;

  public static boolean isRunning = false;

  private WindowManager windowManager = null;
  private FlutterView flutterView;

  private final int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

  private final Handler mAnimationHandler = new Handler();
  private float lastX, lastY;
  private int lastYPosition;
  private boolean dragging;
  private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
  private final Point szWindow = new Point();
  private Timer trayAnimationTimer;

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onDestroy() {
    Log.d(TAG, "Destroying the overlay window service");
    if (windowManager != null) {
      windowManager.removeView(flutterView);
      windowManager = null;
      flutterView.detachFromFlutterEngine();
      flutterView = null;
    }
    isRunning = false;
    NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    mResources = getApplicationContext().getResources();
    boolean isCloseWindow = intent != null && intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
    if (isCloseWindow) {
      if (windowManager != null) {
        windowManager.removeView(flutterView);
        windowManager = null;
        flutterView.detachFromFlutterEngine();
        stopSelf();
      }
      isRunning = false;
      return START_STICKY;
    }
    if (windowManager != null) {
      windowManager.removeView(flutterView);
      windowManager = null;
      flutterView.detachFromFlutterEngine();
      stopSelf();
      return START_NOT_STICKY;
    }

    isRunning = true;
    Log.d(TAG, "onStartCommand, Service started");

    FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
    BinaryMessenger binaryMessenger = engine.getDartExecutor();
    MethodChannel flutterChannel = new MethodChannel(binaryMessenger, OverlayConstants.OVERLAY_TAG);
    BasicMessageChannel<Object> overlayMessageChannel = new BasicMessageChannel<>(binaryMessenger, OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);

    engine.getLifecycleChannel().appIsResumed();

    flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
    flutterView.attachToFlutterEngine(engine);
    flutterView.setFitsSystemWindows(true);
    flutterView.setFocusable(true);
    flutterView.setFocusableInTouchMode(true);
    flutterView.setBackgroundColor(Color.TRANSPARENT);
    flutterChannel.setMethodCallHandler((call, result) -> {
      if (call.method.equals("updateFlag")) {
        String flag = Objects.requireNonNull(call.argument("flag")).toString();
        updateOverlayFlag(result, flag);
      } else if (call.method.equals("resizeOverlay")) {
        int width = call.argument("width");
        int height = call.argument("height");
        resizeOverlay(width, height, result);
      }
    });
    overlayMessageChannel.setMessageHandler((message, reply) -> {
      WindowSetup.messenger.send(message);
    });

    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    windowManager.getDefaultDisplay().getSize(szWindow);
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowSetup.width == -1999 ? -1 : WindowSetup.width, WindowSetup.height != -1999 ? WindowSetup.height : screenHeight(), 0, -statusBarHeightPx(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE, WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
      params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
    }
    params.gravity = WindowSetup.gravity;
    params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    flutterView.setOnTouchListener(this);
    windowManager.addView(flutterView, params);
    return START_STICKY;
  }


  private int screenHeight() {
    Display display = windowManager.getDefaultDisplay();
    DisplayMetrics dm = new DisplayMetrics();
    display.getRealMetrics(dm);
    return inPortrait() ? dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx() : dm.heightPixels + statusBarHeightPx();
  }

  private int statusBarHeightPx() {
    if (mStatusBarHeight == -1) {
      int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

      if (statusBarHeightId > 0) {
        mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
      } else {
        mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
      }
    }

    return mStatusBarHeight;
  }

  int navigationBarHeightPx() {
    if (mNavigationBarHeight == -1) {
      int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

      if (navBarHeightId > 0) {
        mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
      } else {
        mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
      }
    }

    return mNavigationBarHeight;
  }


  private void updateOverlayFlag(MethodChannel.Result result, String flag) {
    if (windowManager != null) {
      WindowSetup.setFlag(flag);
      WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
      params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
        params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
      } else {
        params.alpha = 1;
      }
      windowManager.updateViewLayout(flutterView, params);
      result.success(true);
    } else {
      result.success(false);
    }
  }

  private void resizeOverlay(int width, int height, MethodChannel.Result result) {
    if (windowManager != null) {
      WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
      params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
      params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
      windowManager.updateViewLayout(flutterView, params);
      result.success(true);
    } else {
      result.success(false);
    }
  }


  @Override
  public void onCreate() {
    createNotificationChannel();
    Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
    int pendingFlags;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      pendingFlags = PendingIntent.FLAG_IMMUTABLE;
    } else {
      pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    }
    PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);
    final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
    Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID).setContentTitle(WindowSetup.overlayTitle).setContentText(WindowSetup.overlayContent).setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon).setContentIntent(pendingIntent).setVisibility(WindowSetup.notificationVisibility).build();
    startForeground(OverlayConstants.NOTIFICATION_ID, notification);
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel serviceChannel = new NotificationChannel(OverlayConstants.CHANNEL_ID, "Foreground Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
      NotificationManager manager = getSystemService(NotificationManager.class);
      assert manager != null;
      manager.createNotificationChannel(serviceChannel);
    }
  }

  private int getDrawableResourceId(String resType, String name) {
    return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
  }

  private int dpToPx(int dp) {
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, Float.parseFloat(String.valueOf(dp)), mResources.getDisplayMetrics());
  }

  private boolean inPortrait() {
    return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
  }

  @Override
  public boolean onTouch(View view, MotionEvent event) {
    if (windowManager != null && WindowSetup.enableDrag) {
      WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          dragging = false;
          lastX = event.getRawX();
          lastY = event.getRawY();
          break;
        case MotionEvent.ACTION_MOVE:
          float dx = event.getRawX() - lastX;
          float dy = event.getRawY() - lastY;
          if (!dragging && dx * dx + dy * dy < 25) {
            return false;
          }
          lastX = event.getRawX();
          lastY = event.getRawY();

          boolean invertX = WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT) || WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT) || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
          boolean invertY = WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT) || WindowSetup.gravity == Gravity.BOTTOM || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
          dx = dx * (invertX ? -1 : 1);
          dy = dy * (invertY ? -1 : 1);

          int xx = params.x + (int) dx;
          int yy = params.y + (int) dy;
          params.x = xx;
          params.y = yy;
          windowManager.updateViewLayout(flutterView, params);
          dragging = true;
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          lastYPosition = params.y;
          if (!Objects.equals(WindowSetup.positionGravity, "none")) {
            windowManager.updateViewLayout(flutterView, params);
            if (trayAnimationTimer != null) {
              trayAnimationTimer.cancel();
            }
            trayAnimationTimer = new Timer();
            trayAnimationTimer.schedule(new TrayAnimationTimerTask(), 0, 25);
          }
          return false;
        default:
          return false;
      }
      return false;
    }
    return false;
  }

  private class TrayAnimationTimerTask extends TimerTask {
    int mDestX;
    int mDestY;
    WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

    public TrayAnimationTimerTask() {
      super();
      mDestY = lastYPosition;
      switch (WindowSetup.positionGravity) {
        case "auto":
          mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - flutterView.getWidth();
          if ((WindowSetup.gravity & Gravity.TOP) == Gravity.TOP || (WindowSetup.gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            mDestY = Math.max(mDestY, 0);
            mDestY = Math.min(mDestY, szWindow.y - flutterView.getHeight());
          } else {
            mDestY = Math.min(mDestY, szWindow.y / 2 - flutterView.getHeight());
            mDestY = Math.max(mDestY, -szWindow.y / 2 + flutterView.getHeight());
          }
          break;
        case "left":
          mDestX = 0;
          break;
        case "right":
          mDestX = szWindow.x - flutterView.getWidth();
          break;
        default:
          mDestX = params.x;
          mDestY = params.y;
          break;
      }
    }

    @Override
    public void run() {
      if (windowManager == null) {
        return;
      }
      mAnimationHandler.post(() -> {
        params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
        params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
        windowManager.updateViewLayout(flutterView, params);
        if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
          TrayAnimationTimerTask.this.cancel();
          trayAnimationTimer.cancel();
        }
      });
    }
  }
}
