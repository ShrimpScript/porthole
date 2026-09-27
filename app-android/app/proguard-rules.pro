# sshj resolves transports, ciphers and key formats by name at runtime, so R8 cannot see
# the references and strips them. The failure is not a build error - it is a working debug
# build and a release build that dies mid-handshake, which is the worst way to find out.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.**

# BouncyCastle's providers are looked up reflectively by algorithm name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# sshj pulls optional Bouncy/logging paths that are absent on Android.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
