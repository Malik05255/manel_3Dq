# Manzili HAI release shrinker rules.
# Keep JSON-reflected model classes and OkHttp metadata conservative until
# release instrumentation coverage is broader.
-keepattributes Signature,*Annotation*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin data models are serialized manually, but keep names stable for crash traces.
-keepnames class com.manzili.hai.model.** { *; }
