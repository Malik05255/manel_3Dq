# Manzili HAI release shrinker rules.
# Keep JSON-reflected model classes and OkHttp metadata conservative until
# release instrumentation coverage is broader.
-keepattributes Signature,*Annotation*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink references Error Prone annotations that are compile-time metadata only.
# R8 full mode can report them as missing during minification even though they are
# not runtime dependencies; suppress only that annotation package.
-dontwarn com.google.errorprone.annotations.**

# Kotlin data models are serialized manually, but keep names stable for crash traces.
-keepnames class com.manzili.hai.model.** { *; }
