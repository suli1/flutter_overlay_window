import 'package:android_intent_plus/android_intent.dart';
import 'package:android_intent_plus/flag.dart';
import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

class MessangerChatHead extends StatefulWidget {
  const MessangerChatHead({Key? key}) : super(key: key);

  @override
  State<MessangerChatHead> createState() => _MessangerChatHeadState();
}

class _MessangerChatHeadState extends State<MessangerChatHead> {
  Color color = const Color(0xFFFFFFFF);

  String? _messageFromOverlay;

  @override
  void initState() {
    super.initState();
    FlutterOverlayWindow.overlayListener.listen((event) {
      debugPrint('OverlayWindow > overlay receive message: $event');
      setState(() {
        _messageFromOverlay = 'message from main app: $event';
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      elevation: 0.0,
      child: GestureDetector(
        onTap: () async {
          // if (_currentShape == BoxShape.rectangle) {
          //   await FlutterOverlayWindow.resizeOverlay(50, 100);
          //   setState(() {
          //     _currentShape = BoxShape.circle;
          //   });
          // } else {
          //   await FlutterOverlayWindow.resizeOverlay(
          //     WindowSize.matchParent,
          //     WindowSize.matchParent,
          //   );
          //   setState(() {
          //     _currentShape = BoxShape.rectangle;
          //   });
          // }
          debugPrint('OverlayWindow > share data from overlay');
          await FlutterOverlayWindow.shareData(
              {'type': 'test:${DateTime.now().millisecondsSinceEpoch}'});
          await FlutterOverlayWindow.closeOverlay();
          await const AndroidIntent(
            action: 'action_view',
            category: 'category_launcher',
            package: 'com.example.overlay_window',
            componentName: 'flutter.overlay.window.flutter_overlay_window_example.MainActivity',
            flags: [Flag.FLAG_RECEIVER_FOREGROUND],
          ).launch();
        },
        child: Container(
          height: MediaQuery.of(context).size.height,
          decoration: const BoxDecoration(
            color: Colors.green,
            shape: BoxShape.circle,
          ),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                _messageFromOverlay == null
                    ? const FlutterLogo()
                    : Text(
                        _messageFromOverlay ?? '',
                        textAlign: TextAlign.center,
                      )
              ],
            ),
          ),
        ),
      ),
    );
  }
}
