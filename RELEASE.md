# Android Release

## One-time signing key setup

Run this command in PowerShell from the project directory. Keep the `.jks` file,
alias, and both passwords in a password manager; losing them prevents future
updates to the same Android application.

```powershell
keytool -genkeypair -v -keystore release-keystore.jks -alias planning-app -keyalg RSA -keysize 2048 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and replace all four
values. Both files are intentionally excluded from Git.

## Build a signed release

```powershell
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
```

Outputs:

- Signed APK: `app\build\outputs\apk\release\app-release.apk`
- AAB: `app\build\outputs\bundle\release\app-release.aab`

Without `keystore.properties`, Gradle creates `app-release-unsigned.apk`; do
not distribute it. Use the signed APK for direct installation or a GitHub
Release, and use the signed AAB for Google Play Console. Before every new
release, increase `versionCode` and update `versionName` in
`app/build.gradle.kts`.
