Minimal stand-ins for `androidx.test:monitor` / `espresso-idling-resource`, used only by
`tools/run-tests.sh` in environments where Google's Maven repository is unreachable.
Robolectric needs these classes on the classpath; Gradle/Android Studio builds use the real
artifacts instead. Nothing here is part of the app.
