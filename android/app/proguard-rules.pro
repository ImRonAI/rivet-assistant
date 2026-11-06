# Emrys keeps nothing beyond what AndroidX/OkHttp consumer rules and the manifest already keep.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Ktor, under the phone's MCP server, checks for an attached IntelliJ debugger through java.lang.management, which
# Android doesn't have; R8's own suggested rules.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# The SDK parses responses with Gson into private fields (TokenResponse.token); its own rules keep only public members,
# so without this the voice token arrives blank and release builds can't start a voice session.
-keepclassmembers class io.elevenlabs.** { <fields>; }

# The ElevenLabs SDK's debug logs carry what the agent is sent (contextual updates hold the conversation), so release
# builds drop debug and verbose logging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
