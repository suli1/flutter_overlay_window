package flutter.overlay.window.flutter_overlay_window

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine.EngineLifecycleListener
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.FlutterEngineGroupCache
import io.flutter.embedding.engine.dart.DartExecutor.DartEntrypoint
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener

class FlutterOverlayWindowPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
    ActivityResultListener {

    companion object {
        const val REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248
    }

    private var channel: MethodChannel? = null
    private var context: Context? = null
    private var activity: Activity? = null
    private var pendingResult: MethodChannel.Result? = null

    private var isOverlayEngine: Boolean = true

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, OverlayConstants.CHANNEL_TAG)
        channel!!.setMethodCallHandler(this)

        val overlayEngineGroup = ensureEngineGroupCreated(context!!)
        isOverlayEngine = flutterPluginBinding.engineGroup == overlayEngineGroup
        registerMessageChannel(isOverlayEngine, flutterPluginBinding.binaryMessenger)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        pendingResult = result
        when (call.method) {
            "checkPermission" -> {
                result.success(checkOverlayPermission())
            }

            "requestPermission" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.setData(Uri.parse("package:" + activity!!.packageName))
                    activity!!.startActivityForResult(intent, REQUEST_CODE_FOR_OVERLAY_PERMISSION)
                } else {
                    result.success(true)
                }
            }

            "showOverlay" -> {
                if (!checkOverlayPermission()) {
                    result.error("PERMISSION", "overlay permission is not enabled", null)
                    return
                }
                val height = call.argument<Int>("height")
                val width = call.argument<Int>("width")
                val alignment = call.argument<String>("alignment")
                val flag = call.argument<String>("flag")
                val overlayTitle = call.argument<String>("overlayTitle")
                val overlayContent = call.argument<String>("overlayContent")
                val notificationVisibility = call.argument<String>("notificationVisibility")!!
                val enableDrag = call.argument<Boolean>("enableDrag")!!
                val positionGravity = call.argument<String>("positionGravity")!!
                WindowSetup.width = width ?: -1
                WindowSetup.height = height ?: -1
                WindowSetup.enableDrag = enableDrag
                WindowSetup.setGravityFromAlignment(alignment ?: "center")
                WindowSetup.setFlag(flag ?: "flagNotFocusable")
                WindowSetup.overlayTitle = overlayTitle ?: ""
                WindowSetup.overlayContent = overlayContent ?: ""
                WindowSetup.positionGravity = positionGravity
                WindowSetup.setNotificationVisibility(notificationVisibility)
                val intent = Intent(context, OverlayService::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context!!.startService(intent)
                result.success(null)
            }

            "isOverlayActive" -> {
                result.success(OverlayService.isRunning)
            }

            "closeOverlay" -> {
                if (OverlayService.isRunning) {
                    val i = Intent(context, OverlayService::class.java)
                    i.putExtra(OverlayService.INTENT_EXTRA_IS_CLOSE_WINDOW, true)
                    context!!.startService(i)
                    result.success(true)
                }
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        unregisterMessageChannel(isOverlayEngine)
        FlutterEngineGroupCache.getInstance().remove(OverlayConstants.CACHED_TAG)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        ensureEngineCreated(context!!)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_FOR_OVERLAY_PERMISSION) {
            pendingResult!!.success(checkOverlayPermission())
            return true
        }
        return false
    }

    private fun ensureEngineGroupCreated(context: Context): FlutterEngineGroup {
        var enn = FlutterEngineGroupCache.getInstance().get(OverlayConstants.CACHED_TAG)
        if (enn == null) {
            enn = FlutterEngineGroup(context)
            FlutterEngineGroupCache.getInstance().put(OverlayConstants.CACHED_TAG, enn)
        }
        return enn
    }

    private fun ensureEngineCreated(context: Context) {
        var engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG)
        if (engine == null) {
            val enn = ensureEngineGroupCreated(context)
            val dEntry = DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(), "overlayMain"
            )
            engine = enn.createAndRunEngine(context, dEntry)
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine)
            engine.addEngineLifecycleListener(object : EngineLifecycleListener {
                override fun onPreEngineRestart() {}
                override fun onEngineWillDestroy() {
                    FlutterEngineCache.getInstance().remove(OverlayConstants.CACHED_TAG)
                }
            })
        }
    }

    private fun registerMessageChannel(isOverlayEngine: Boolean, binaryMessenger: BinaryMessenger) {
        if (isOverlayEngine) {
            registerOverlayMessageChannel(binaryMessenger)
        } else {
            if (CachedMessageChannels.mainAppMessageChannel == null) {
                registerMainAppMessageChannel(binaryMessenger)
            }
        }
    }

    private fun registerMainAppMessageChannel(binaryMessenger: BinaryMessenger) {
        val messageChannel = MethodChannel(binaryMessenger, OverlayConstants.MESSENGER_TAG)
        messageChannel.setMethodCallHandler { call, result ->
            if (CachedMessageChannels.overlayMessageChannel != null) {
                CachedMessageChannels.overlayMessageChannel?.invokeMethod("message", call.arguments)
                result.success(true)
            } else {
                result.success(false)
            }

        }
        CachedMessageChannels.mainAppMessageChannel = messageChannel
    }

    private fun registerOverlayMessageChannel(binaryMessenger: BinaryMessenger) {
        val messageChannel = MethodChannel(binaryMessenger, OverlayConstants.MESSENGER_TAG)
        messageChannel.setMethodCallHandler { call, result ->
            if (CachedMessageChannels.mainAppMessageChannel != null) {
                CachedMessageChannels.mainAppMessageChannel?.invokeMethod("message", call.arguments)
                result.success(true)
            } else {
                result.success(false)
            }

        }
        CachedMessageChannels.overlayMessageChannel = messageChannel
    }

    private fun unregisterMessageChannel(isMainAppEngine: Boolean) {
        if (isMainAppEngine) {
            CachedMessageChannels.mainAppMessageChannel?.setMethodCallHandler(null)
            CachedMessageChannels.mainAppMessageChannel = null
        } else {
            CachedMessageChannels.overlayMessageChannel?.setMethodCallHandler(null)
            CachedMessageChannels.overlayMessageChannel = null
        }
    }

}
