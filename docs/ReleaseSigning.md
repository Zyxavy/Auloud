# Auloud Player release signing (CP8)

1. Generate the key ONCE on your PC (NEVER in the repo):
   `keytool -genkeypair -v -keystore D:\keys\auloud-release.keystore -alias auloud -keyalg RSA -keysize 2048 -validity 10000`
2. Keep the `.keystore` file OUTSIDE the repo (for example `D:\keys\`).
3. Back it up (for example a USB stick kept elsewhere) and note the
   passwords somewhere safe. Losing it means the app must be REINSTALLED:
   updates signed with a new key will not install over the old app.
4. Point the build at it in `player\local.properties` (gitignored, never
   commit), backslashes doubled:
   `auloud.keystore.path=D:\\keys\\auloud-release.keystore`
   `auloud.keystore.storePassword=<store password>`
   `auloud.keystore.keyAlias=auloud`
   `auloud.keystore.keyPassword=<key password, or omit if same as store>`
5. Without these keys the release build signs with the debug key and prints
   a WARNING, so CI and unit tests never break.
6. Build: `gradlew.bat :app:assembleRelease` in `player\`.
7. Verify before sideloading: read the merger report at
   `app\build\outputs\logs\manifest-merger-release-report.txt` and run
   `<sdk>\build-tools\<version>\aapt.exe dump badging <apk> | findstr permission`
   (must list NO `android.permission.INTERNET`), then install on the Tab E
   (allow unknown sources) and re-run the Slice 1-4 smoke checks.
