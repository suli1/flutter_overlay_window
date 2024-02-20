import 'dart:async';
import 'dart:developer';

import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/src/overlay_config.dart';

class FlutterOverlayWindow {
  FlutterOverlayWindow._();

  static final StreamController _controller = StreamController.broadcast();
  static const MethodChannel _channel = MethodChannel("x-slayer/overlay_channel");
  static const MethodChannel _overlayChannel = MethodChannel("x-slayer/overlay");
  static const MethodChannel _overlayMessageChannel = MethodChannel("x-slayer/overlay_messenger");

  /// Open overLay content
  ///
  /// - Optional arguments:
  /// `height` the overlay height and default is [WindowSize.fullCover]
  /// `width` the overlay width and default is [WindowSize.matchParent]
  /// `alignment` the alignment position on screen and default is [OverlayAlignment.center]
  /// `visibilitySecret` the detail displayed in notifications on the lock screen and default is [NotificationVisibility.visibilitySecret]
  /// `OverlayFlag` the overlay flag and default is [OverlayFlag.defaultFlag]
  /// `overlayTitle` the notification message and default is "overlay activated"
  /// `overlayContent` the notification message
  /// `enableDrag` to enable/disable dragging the overlay over the screen and default is "false"
  /// `positionGravity` the overlay position after drag and default is [PositionGravity.none]
  static Future<void> showOverlay({
    int height = WindowSize.fullCover,
    int width = WindowSize.matchParent,
    OverlayAlignment alignment = OverlayAlignment.center,
    NotificationVisibility visibility = NotificationVisibility.visibilitySecret,
    OverlayFlag flag = OverlayFlag.defaultFlag,
    String overlayTitle = "overlay activated",
    String? overlayContent,
    bool enableDrag = false,
    PositionGravity positionGravity = PositionGravity.none,
  }) async {
    await _channel.invokeMethod(
      'showOverlay',
      {
        "height": height,
        "width": width,
        "alignment": alignment.name,
        "flag": flag.name,
        "overlayTitle": overlayTitle,
        "overlayContent": overlayContent,
        "enableDrag": enableDrag,
        "notificationVisibility": visibility.name,
        "positionGravity": positionGravity.name,
      },
    );
  }

  /// Check if overlay permission is granted
  static Future<bool> isPermissionGranted() async {
    try {
      return await _channel.invokeMethod<bool>('checkPermission') ?? false;
    } on PlatformException catch (error) {
      log("$error");
      return Future.value(false);
    }
  }

  /// Request overlay permission
  /// it will open the overlay settings page and return `true` once the permission granted.
  static Future<bool?> requestPermission() async {
    try {
      return await _channel.invokeMethod<bool?>('requestPermission');
    } on PlatformException catch (error) {
      log("Error requestPermission: $error");
      rethrow;
    }
  }

  /// Closes overlay if open
  static Future<bool?> closeOverlay() async {
    final bool? res = await _channel.invokeMethod('closeOverlay');
    return res;
  }

  /// Broadcast data to and from overlay app
  static Future<bool> shareData(dynamic data) async {
    return await _overlayMessageChannel.invokeMethod('', data);
  }

  /// Streams message shared between overlay and main app
  static Stream<dynamic> get overlayListener {
    _overlayMessageChannel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'message':
          _controller.add(call.arguments);
          break;
      }
    });
    return _controller.stream;
  }

  /// Update the overlay flag while the overlay in action
  static Future<bool?> updateFlag(OverlayFlag flag) async {
    final bool? res = await _overlayChannel.invokeMethod<bool?>('updateFlag', {'flag': flag.name});
    return res;
  }

  /// Update the overlay size in the screen
  static Future<bool?> resizeOverlay(int width, int height) async {
    final bool? res = await _overlayChannel.invokeMethod<bool?>(
      'resizeOverlay',
      {
        'width': width,
        'height': height,
      },
    );
    return res;
  }

  /// Check if the current overlay is active
  static Future<bool> isActive() async {
    final bool? res = await _channel.invokeMethod<bool?>('isOverlayActive');
    return res ?? false;
  }

  /// Dispose overlay stream
  static void disposeOverlayListener() {
    _controller.close();
  }
}
