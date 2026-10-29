# Rivet keeps nothing beyond what AndroidX/OkHttp consumer rules and the manifest already keep.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The ElevenLabs SDK's debug logs carry what the agent is sent (contextual updates hold the conversation), so release
# builds drop debug and verbose logging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
