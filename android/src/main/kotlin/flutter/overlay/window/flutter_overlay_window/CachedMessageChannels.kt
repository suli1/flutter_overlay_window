package flutter.overlay.window.flutter_overlay_window

import io.flutter.plugin.common.MethodChannel

object CachedMessageChannels {
    var mainAppMessageChannel: MethodChannel? = null
    var overlayMessageChannel: MethodChannel? = null
}
