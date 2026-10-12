# Retrofit 3, kotlinx.serialization, OkHttp and MapLibre ship consumer keep rules in their artifacts.
# AGP 9 runs R8 in strict full mode: to keep a default constructor use `-keep class X { <init>(); }`.

# Network layer: AuthInterceptor/TokenAuthenticator read @Unauthenticated from Retrofit service methods at runtime.
# Without these, R8 may drop the annotation and login/register/refresh would be sent as authenticated calls.
-keepattributes RuntimeVisibleAnnotations
-keep @interface com.eldering.safety.network.Unauthenticated
