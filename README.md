# Glass Launcher (Mi Box)

A small custom home screen: black night-city wallpaper with twinkling lights,
clock and date, a dock with Netflix, YouTube and Hotstar, an app grid, and a
springy glass highlight that glides between tiles.

Dock apps are set at the top of `LauncherView.java` (DOCK_PKGS).

## Build with GitHub Actions
1. Create a free account at github.com and sign in.
2. Click "+" > "New repository". Name it glasslauncher. Click "Create repository".
3. Click "uploading an existing file". Drag in everything from this folder
   (app, build.gradle, settings.gradle, gradle.properties, README.md).
   Click "Commit changes".
4. The hidden folder .github is easy to miss. Click "Add file" > "Create new file",
   type  .github/workflows/build.yml  as the name, paste the contents of that file, commit.
5. Open the "Actions" tab. The "Build APK" run starts by itself (about 3-5 minutes).
   If it does not, click "Build APK" > "Run workflow".
6. When it shows a green tick, open the run and download "glasslauncher-apk" at the bottom.
   Unzip it. You get app-debug.apk.
7. Put app-debug.apk in your platform-tools folder, next to adb.exe, then run install_launcher.bat.

## Undo
Run use_google_launcher.bat to go back to the Google home screen.
