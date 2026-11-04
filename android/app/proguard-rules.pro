# Swell release rules. Libraries with JNI or reflection are kept whole.
# libsignal: Rust code calls back into these classes by name.
-keep class org.signal.libsignal.** { *; }
# Tor (TorService, JNI) and the control library (reflection on sendAndWaitForResponse / ReplyLine.msg).
-keep class org.torproject.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keep class info.guardianproject.** { *; }
# SQLCipher (JNI).
-keep class net.zetetic.** { *; }
# Swell core: small, and parts are used across modules.
-keep class com.jijilabsdigital.swell.core.** { *; }
-dontwarn org.signal.libsignal.**
-dontwarn org.torproject.**
-dontwarn net.freehaven.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
