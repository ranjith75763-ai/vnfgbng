KICK LIVE ALERT - ANDROID

This is the source code of the app. It has to be turned into an APK once (a phone cannot
install source code). Pick ONE way:

A) ANDROID STUDIO (on a PC, ~10 minutes the first time)
   1. Install Android Studio (developer.android.com/studio).
   2. File > Open > choose this folder. Wait for "Gradle sync" to finish (downloads stuff).
   3. Menu: Build > Build Bundle(s) / APK(s) > Build APK(s).
   4. Click "locate" in the popup. The file is app-debug.apk.
   5. Send app-debug.apk to your phone, tap it, allow "install unknown apps", install.

B) GITHUB (no Android Studio)
   1. Make a free account on github.com, create a new repository, upload ALL files of this
      folder (including the hidden .github folder).
   2. Open the Actions tab > "Build APK" > wait ~5 min (or press Run workflow).
   3. Open the finished run, download "KickLiveAlert-apk" (a zip), unzip, install app-debug.apk.

FIRST OPEN ON THE PHONE
   - Allow notifications.
   - Tap "Allow" when it asks to ignore battery optimization (needed so it keeps checking
     with the screen off).
   - Press TEST ALERT to confirm you hear the siren.
   - Change streamers in the box, then SAVE & RESTART WATCHING.
