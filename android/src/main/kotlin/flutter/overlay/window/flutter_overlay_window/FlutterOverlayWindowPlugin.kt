package flutter.overlay.window.flutter_overlay_window

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor.DartEntrypoint
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.JSONMessageCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener

class FlutterOverlayWindowPlugin : FlutterPlugin, ActivityAware,
    BasicMessageChannel.MessageHandler<Any?>, MethodCallHandler, ActivityResultListener {

    companion object {
        const val REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248
    }

    private var channel: MethodChannel? = null
    private var context: Context? = null
    private var mActivity: Activity? = null
    private var messenger: BasicMessageChannel<Any>? = null
    private var pendingResult: MethodChannel.Result? = null


    override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, OverlayConstants.CHANNEL_TAG)
        channel!!.setMethodCallHandler(this)
        messenger = BasicMessageChannel(
            flutterPluginBinding.binaryMessenger,
            OverlayConstants.MESSENGER_TAG,
            JSONMessageCodec.INSTANCE
        )
        messenger!!.setMessageHandler(this)
        WindowSetup.messenger = messenger
        WindowSetup.messenger!!.setMessageHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        pendingResult = result
        if (call.method == "checkPermission") {
            result.success(checkOverlayPermission())
        } else if (call.method == "requestPermission") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.setData(Uri.parse("package:" + mActivity!!.packageName))
                mActivity!!.startActivityForResult(intent, REQUEST_CODE_FOR_OVERLAY_PERMISSION)
            } else {
                result.success(true)
            }
        } else if (call.method == "showOverlay") {
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
        } else if (call.method == "isOverlayActive") {
            result.success(OverlayService.isRunning)
        } else if (call.method == "closeOverlay") {
            if (OverlayService.isRunning) {
                val i = Intent(context, OverlayService::class.java)
                i.putExtra(OverlayService.INTENT_EXTRA_IS_CLOSE_WINDOW, true)
                context!!.startService(i)
                result.success(true)
            }
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        WindowSetup.messenger?.setMessageHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity
        val enn = FlutterEngineGroup(context!!)
        val dEntry = DartEntrypoint(
            FlutterInjector.instance().flutterLoader().findAppBundlePath(), "overlayMain"
        )
        val engine = enn.createAndRunEngine(context!!, dEntry)
        FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        mActivity = binding.activity
    }

    override fun onDetachedFromActivity() {}

    override fun onMessage(message: Any?, reply: BasicMessageChannel.Reply<Any?>) {
        val overlayMessageChannel: BasicMessageChannel<Any?> = BasicMessageChannel(
            FlutterEngineCache.getInstance()[OverlayConstants.CACHED_TAG]!!.dartExecutor,
            OverlayConstants.MESSENGER_TAG,
            JSONMessageCodec.INSTANCE
        )
        overlayMessageChannel.send(message, reply)
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
}
