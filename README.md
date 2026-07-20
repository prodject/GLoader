# GLoader

<p align="center">
  <img src="assets/gloader-logo-transparent.png" alt="GLoader logo" width="240">
</p>

**English** · [Русский](README_RU.MD)

GLoader is a compact initial APK installer and cleanup helper for the Geely Citiray head unit running Android 11 or newer. It automatically searches connected removable USB drives for `.apk` files, displays everything it finds, lets the user select an APK manually through the system file picker, lists installed applications, and can remove applications previously installed through GLoader.

The interface is designed for both phone displays, including the Pixel 6 Pro, and the large 13.2-inch vertical head-unit screen. The project uses only native Android APIs and no heavy third-party libraries, keeping the resulting APK small enough to install during a short ADB availability window.

## Initial installation over ADB

You need `adb`, a USB OTG connection, and a built APK from [GitHub Releases](https://github.com/prodject/GLoader/releases).

```sh
chmod +x install.sh
./install.sh ./GLoader-1.X.apk
```

The script waits for the device, quickly copies the APK to `/data/local/tmp`, runs `pm install`, attempts to grant external-storage and package-installation access through `appops`, removes the temporary file, and launches GLoader. Whether the `appops` commands succeed depends on the permissions available to the ADB shell in the particular head-unit firmware.

## Usage

1. Connect a USB drive containing APK files and launch GLoader.
2. Open **Installer**. GLoader automatically scans all accessible removable storage volumes.
3. Select **Install** next to a detected APK, or use **Select APK** to choose a file manually.
4. On first use, Android may ask you to grant all-files access and allow installation from this source.

Android does not permit a regular user application to install APK files silently. Unless GLoader is installed as a system application or configured as a Device Owner, Android's standard installation confirmation remains mandatory.

The **Apps** tab shows installed packages visible to GLoader. User applications can be opened for Android's normal uninstall confirmation flow; protected system packages are shown as non-removable.

The **Cleanup** tab tracks packages that were successfully installed through GLoader. It can request removal of those tracked applications one by one, then optionally request removal of GLoader itself. Android asks for confirmation for every uninstall action. When GLoader is uninstalled, Android also removes GLoader's private app data.

Important limitation: a regular Android application cannot erase system Package Manager records, installation history, or system logs. Cleanup only covers tracked applications, GLoader's own uninstall request, and GLoader's private app data that Android deletes during uninstall.

## Builds and releases

GitHub Actions builds a minimized release APK whenever the workflow runs on the `main` branch and publishes a `1.X` release, where `X` is the automatically incremented Actions run number. The APK is signed with Android's standard debug key for direct installation. Configure a private production signing key before distributing the application through an app store.

The project is intended to be built by the included GitHub Actions workflow. A local build, if required, needs JDK 17, Android SDK 35, and Gradle 8.9:

```sh
gradle assembleRelease
```

## Disclaimer

This project is unofficial and is not affiliated with or endorsed by Geely Holding Group. Installing third-party applications, enabling ADB, or modifying the settings or software of a vehicle head unit is done entirely at your own risk. These actions may cause instability, data loss, reduced security, loss of official updates, and full or partial refusal of warranty service for the vehicle and/or its head unit by the manufacturer or dealer. Review your warranty terms and applicable laws before using this software. The project authors accept no liability for damage to the vehicle, head unit, data, or third parties.

## License

See [LICENSE](LICENSE).
