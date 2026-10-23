# Building the app

There are no prebuilt APKs. You build the app on your computer and install it on your phone over USB.

You need a computer running Linux, macOS or [WSL](https://learn.microsoft.com/windows/wsl/install) on Windows,
a USB cable and a phone with Android 13 or newer.

## Install

1. This app replaces AndroidAPS. In AndroidAPS, export your settings, then uninstall it. Uninstalling deletes
   its data from the phone.
2. Install [Git](https://git-scm.com/downloads) and [mise](https://mise.jdx.dev/getting-started.html).
3. Open a terminal and download the code:

   ```bash
   git clone https://github.com/Baz00k/AndroidAPS.git
   cd AndroidAPS
   ```

4. Install the build tools and accept the [Android SDK terms](https://developer.android.com/studio/terms):

   ```bash
   mise trust
   mise install
   mise exec -- android sdk install platform-tools
   ```

5. On the phone, turn on [USB debugging](https://developer.android.com/studio/debug/dev-options#enable).
   Connect the phone to the computer and allow debugging when the phone asks.
6. Build and install the app. The first build takes a while.

   ```bash
   mise run install
   ```

7. Open the app and set it up as described in the [AndroidAPS documentation](https://wiki.aaps.app/en/latest/).

## Update

Connect the phone and run:

```bash
cd AndroidAPS
git pull
mise run install
```

Build updates on the same computer. Android installs an update only if it was built with the same key as the
installed app.
