# RoohuX — development source (NOT a finished APK)

Native Android Kotlin + Compose project. This is a **real, partial implementation**:
- Direct HTTP(S) media playback through Media3 (MP4/MP3/HLS/DASH depending on source and server)
- Built-in WebView browser with JavaScript and HTML5 website playback
- URL input, local saved-link library and removal
- Dark UI with Home / Player / Browser / Saved tabs

**Not yet implemented:** Google Drive OAuth or browsing, video auto-detection from webpages,
downloads, background media service, Picture-in-Picture, casting, playlists, offline playback,
subtitle selection UI, advanced privacy controls. Browser "Play direct URL" only works when
the current address itself ends in a recognized media extension.

## Build requirements
Android Studio with Android SDK 35, JDK 17 and Gradle 8.9.
Open the project directory and sync Gradle. A Gradle wrapper and APK are **not included**.
No build or device test was possible in this environment. Build success is **not verified**.

## Safety
Use only URLs you are permitted to play. This app does not bypass DRM, sign-in,
site restrictions or protected streams. The browser has JavaScript enabled; only
browse sites you trust. The app does not currently handle browser file uploads,
camera permissions, downloads, or advanced fullscreen video callbacks.

## Next development tasks
1. Verify compilation with Android SDK; fix actual compiler/build feedback.
2. Implement a browser full-screen HTML5 video WebChromeClient.
3. Add user-consented detection of standard HTML5 video URLs.
4. Add Google Drive sign-in and browsing after OAuth setup.
5. Add media foreground service, offline downloads, and library metadata.

Developer: Roohu
Support: roohukhan01@gmail.com
