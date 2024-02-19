package flutter.overlay.window.flutter_overlay_window

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.JSONMessageCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.*

class OverlayService : Service(), OnTouchListener {
    private var mStatusBarHeight = -1
    private var mNavigationBarHeight = -1
    private var mResources: Resources? = null
    private var windowManager: WindowManager? = null
    private var flutterView: FlutterView? = null
    private val clickableFlag =
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    private val mAnimationHandler = Handler()
    private var lastX = 0f
    private var lastY = 0f
    private var lastYPosition = 0
    private var dragging = false
    private val szWindow = Point()
    private var trayAnimationTimer: Timer? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    override fun onDestroy() {
        Log.d(TAG, "Destroying the overlay window service")
        if (windowManager != null) {
            windowManager!!.removeView(flutterView)
            windowManager = null
            flutterView!!.detachFromFlutterEngine()
            flutterView = null
        }
        isRunning = false
        val notificationManager =
            applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false)
        if (isCloseWindow) {
            if (windowManager != null) {
                windowManager!!.removeView(flutterView)
                windowManager = null
                flutterView!!.detachFromFlutterEngine()
                stopSelf()
            }
            isRunning = false
            return START_STICKY
        }
        if (windowManager != null) {
            windowManager!!.removeView(flutterView)
            windowManager = null
            flutterView!!.detachFromFlutterEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        Log.d(TAG, "onStartCommand, Service started")

        mResources = applicationContext.resources
        val engine = FlutterEngineCache.getInstance()[OverlayConstants.CACHED_TAG]
        val binaryMessenger: BinaryMessenger = engine!!.dartExecutor
        val flutterChannel = MethodChannel(binaryMessenger, OverlayConstants.OVERLAY_TAG)
        val overlayMessageChannel = BasicMessageChannel(
            binaryMessenger,
            OverlayConstants.MESSENGER_TAG,
            JSONMessageCodec.INSTANCE
        )
        engine.lifecycleChannel.appIsResumed()
        flutterView = FlutterView(
            applicationContext, FlutterTextureView(
                applicationContext
            )
        )
        flutterView!!.attachToFlutterEngine(engine)
        flutterView!!.fitsSystemWindows = true
        flutterView!!.isFocusable = true
        flutterView!!.isFocusableInTouchMode = true
        flutterView!!.setBackgroundColor(Color.TRANSPARENT)
        flutterChannel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            if (call.method == "updateFlag") {
                val flag = Objects.requireNonNull(call.argument<Any>("flag")).toString()
                updateOverlayFlag(result, flag)
            } else if (call.method == "resizeOverlay") {
                val width = call.argument<Int>("width")!!
                val height = call.argument<Int>("height")!!
                resizeOverlay(width, height, result)
            }
        }
        overlayMessageChannel.setMessageHandler { message, _ -> WindowSetup.messenger?.send(message) }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager!!.defaultDisplay.getSize(szWindow)
        val params = WindowManager.LayoutParams(
            if (WindowSetup.width == -1999) -1 else WindowSetup.width,
            if (WindowSetup.height != -1999) WindowSetup.height else screenHeight(),
            0,
            -statusBarHeightPx(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowSetup.flag or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER
        }
        params.gravity = WindowSetup.gravity
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        flutterView!!.setOnTouchListener(this)
        windowManager!!.addView(flutterView, params)
        return START_STICKY
    }

    private fun screenHeight(): Int {
        val display = windowManager!!.defaultDisplay
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        return if (inPortrait()) dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx() else dm.heightPixels + statusBarHeightPx()
    }

    private fun statusBarHeightPx(): Int {
        if (mStatusBarHeight == -1) {
            val statusBarHeightId =
                mResources!!.getIdentifier("status_bar_height", "dimen", "android")
            mStatusBarHeight = if (statusBarHeightId > 0) {
                mResources!!.getDimensionPixelSize(statusBarHeightId)
            } else {
                dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP)
            }
        }
        return mStatusBarHeight
    }

    private fun navigationBarHeightPx(): Int {
        if (mNavigationBarHeight == -1) {
            val navBarHeightId =
                mResources!!.getIdentifier("navigation_bar_height", "dimen", "android")
            mNavigationBarHeight = if (navBarHeightId > 0) {
                mResources!!.getDimensionPixelSize(navBarHeightId)
            } else {
                dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP)
            }
        }
        return mNavigationBarHeight
    }

    private fun updateOverlayFlag(result: MethodChannel.Result, flag: String) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag)
            val params = flutterView!!.layoutParams as WindowManager.LayoutParams
            params.flags =
                WindowSetup.flag or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER
            } else {
                params.alpha = 1f
            }
            windowManager!!.updateViewLayout(flutterView, params)
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun resizeOverlay(width: Int, height: Int, result: MethodChannel.Result) {
        if (windowManager != null) {
            val params = flutterView!!.layoutParams as WindowManager.LayoutParams
            params.width = if (width == -1999 || width == -1) -1 else dpToPx(width)
            params.height = if (height != 1999 && height != -1) dpToPx(height) else height
            windowManager!!.updateViewLayout(flutterView, params)
            result.success(true)
        } else {
            result.success(false)
        }
    }

    override fun onCreate() {
        createNotificationChannel()
        val notificationIntent = Intent(this, FlutterOverlayWindowPlugin::class.java)
        val pendingFlags: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags)
        val notifyIcon = getDrawableResourceId("mipmap", "launcher")
        val notification = NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
            .setContentTitle(WindowSetup.overlayTitle).setContentText(WindowSetup.overlayContent)
            .setSmallIcon(if (notifyIcon == 0) R.drawable.notification_icon else notifyIcon)
            .setContentIntent(pendingIntent).setVisibility(WindowSetup.notificationVisibility)
            .build()
        startForeground(OverlayConstants.NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                OverlayConstants.CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(
                NotificationManager::class.java
            )!!
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getDrawableResourceId(resType: String, name: String): Int {
        return applicationContext.resources.getIdentifier(
            String.format("ic_%s", name),
            resType,
            applicationContext.packageName
        )
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toString().toFloat(),
            mResources!!.displayMetrics
        ).toInt()
    }

    private fun inPortrait(): Boolean {
        return mResources!!.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (windowManager != null && WindowSetup.enableDrag) {
            val params = flutterView!!.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    lastX = event.rawX
                    lastY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    var dx = event.rawX - lastX
                    var dy = event.rawY - lastY
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    val invertX =
                        WindowSetup.gravity == Gravity.TOP or Gravity.RIGHT || WindowSetup.gravity == Gravity.CENTER or Gravity.RIGHT || WindowSetup.gravity == Gravity.BOTTOM or Gravity.RIGHT
                    val invertY =
                        WindowSetup.gravity == Gravity.BOTTOM or Gravity.LEFT || WindowSetup.gravity == Gravity.BOTTOM || WindowSetup.gravity == Gravity.BOTTOM or Gravity.RIGHT
                    dx *= if (invertX) -1 else 1
                    dy *= if (invertY) -1 else 1
                    val xx = params.x + dx.toInt()
                    val yy = params.y + dy.toInt()
                    params.x = xx
                    params.y = yy
                    windowManager!!.updateViewLayout(flutterView, params)
                    dragging = true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastYPosition = params.y
                    if (WindowSetup.positionGravity != "none") {
                        windowManager!!.updateViewLayout(flutterView, params)
                        if (trayAnimationTimer != null) {
                            trayAnimationTimer!!.cancel()
                        }
                        trayAnimationTimer = Timer()
                        trayAnimationTimer!!.schedule(TrayAnimationTimerTask(), 0, 25)
                    }
                    return false
                }

                else -> return false
            }
            return false
        }
        return false
    }

    private inner class TrayAnimationTimerTask : TimerTask() {
        var mDestX = 0
        var mDestY: Int
        var params = flutterView!!.layoutParams as WindowManager.LayoutParams

        init {
            mDestY = lastYPosition
            when (WindowSetup.positionGravity) {
                "auto" -> {
                    mDestX =
                        if (params.x + flutterView!!.width / 2 <= szWindow.x / 2) 0 else szWindow.x - flutterView!!.width
                    if (WindowSetup.gravity and Gravity.TOP == Gravity.TOP || WindowSetup.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
                        mDestY = mDestY.coerceAtLeast(0)
                        mDestY = mDestY.coerceAtMost(szWindow.y - flutterView!!.height)
                    } else {
                        mDestY = mDestY.coerceAtMost(szWindow.y / 2 - flutterView!!.height)
                        mDestY = mDestY.coerceAtLeast(-szWindow.y / 2 + flutterView!!.height)
                    }
                }

                "left" -> mDestX = 0
                "right" -> mDestX = szWindow.x - flutterView!!.width
                else -> {
                    mDestX = params.x
                    mDestY = params.y
                }
            }
        }

        override fun run() {
            if (windowManager == null) {
                return
            }
            mAnimationHandler.post {
                params.x = 2 * (params.x - mDestX) / 3 + mDestX
                params.y = 2 * (params.y - mDestY) / 3 + mDestY
                windowManager!!.updateViewLayout(flutterView, params)
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    cancel()
                    trayAnimationTimer!!.cancel()
                }
            }
        }
    }

    companion object {
        const val INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow"
        private const val TAG = "OverlayService"
        private const val DEFAULT_NAV_BAR_HEIGHT_DP = 48
        private const val DEFAULT_STATUS_BAR_HEIGHT_DP = 25
        var isRunning = false
        private const val MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f
    }
}
