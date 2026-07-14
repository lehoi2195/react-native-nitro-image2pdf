# PdfBox-Android uses BouncyCastle via reflection for AES-128 encryption
# (StandardProtectionPolicy). Without these keep rules, minified release
# builds crash at runtime when `security.password`/`ownerPassword` is set.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# PdfBox-Android's own resource/font subsystem also relies on reflection.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
