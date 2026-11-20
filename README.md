# Clap Finder

Lost your phone? Clap twice and it rings, shouts "I'm over here!", flashes the torch and vibrates, even with the screen off.

## Install

Download the latest APK on your Android phone and open it:
https://github.com/TimTam23456/ClapFinder/releases/latest/download/ClapFinder.apk

Android will ask you to allow installing apps from your browser the first time.

## How it works

- A foreground service keeps the microphone open and looks for claps: sudden, sharp, bright sounds that die away within about 0.13 s.
- Talking, music, typing and knocking don't count. It needs 2 (or 3) claps between 0.12 and 1 s apart, then half a second without more claps.
- The alarm plays on the alarm channel at full volume, so it works on silent.

Every push to `main` builds a new APK with GitHub Actions and publishes it as a release.
The signing key in `app/` is a throwaway key so updates install over the old version.
