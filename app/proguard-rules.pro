# LibVLC resolves its Java classes and members from native code.
# Matches VideoLAN's application/app/proguard-rules.pro.
-keep class org.videolan.libvlc.** { *; }
