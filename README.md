# GLoader

<p align="center">
  <img src="assets/gloader-logo-transparent.png" alt="GLoader logo" width="240">
</p>

**English** · [Русский](README_RU.MD)

GLoader is a compact initial APK installer and cleanup helper for the Geely Citiray head unit running Android 11 or newer. It automatically searches internal shared storage and connected removable USB drives for `.apk` files, displays everything it finds, lets the user select an APK manually through the system file picker, lists installed applications, and can remove applications previously installed through GLoader.

The interface is designed for both phone displays, including the Pixel 6 Pro, and the large 13.2-inch vertical head-unit screen. The project uses only native Android APIs and no heavy third-party libraries, keeping the resulting APK small enough to install during a short ADB availability window.

## Initial installation over ADB

You need `adb`, a USB OTG connection, and a built APK from [GitHub Releases](https://github.com/prodject/GLoader/releases).

```sh
chmod +x install.sh
./install.sh ./GLoader-1.X.apk
```

The script waits for the device, quickly copies the APK to `/data/local/tmp`, runs `pm install`, attempts to grant external-storage and package-installation access through `appops`, also attempts the legacy read-storage grant, removes the temporary file, and launches GLoader. Whether the `appops` and `pm grant` commands succeed depends on the permissions available to the ADB shell in the particular head-unit firmware.

GitHub Actions release APKs are signed with a checked-in debug release key at `app/keystore/gloader-release-debug.p12`, so future Actions builds are update-compatible with each other. Do not use this key for Play Store or private production distribution.

If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the already installed `com.prodject.gloader` package was signed with a different key, for example by an older workflow or a local debug build. Android cannot update such an APK in place. Remove the old package first, or let the installer do that explicitly:

```sh
./uninstall.sh
./install.sh ./GLoader-1.X.apk

# or
./install.sh --replace-incompatible ./GLoader-1.X.apk
```

`uninstall.sh` stops GLoader, clears its private app data, uninstalls the package, removes temporary installer files from `/data/local/tmp`, and attempts to clear the device logcat buffer if ADB has permission. ADB cannot guarantee removal of Android Package Manager history, audit records, or firmware/vendor logs.

## Usage

1. Connect a USB drive containing APK files and launch GLoader.
2. Open **Installer**. GLoader automatically scans accessible internal shared storage and connected removable storage volumes, then lets you switch the results between internal and external APKs.
3. Select **Install** next to a detected APK, or use **Select APK** to choose a file manually.
4. On first use, Android may ask you to grant all-files access and allow installation from this source.

Android does not permit a regular user application to install APK files silently. Unless GLoader is installed as a system application or configured as a Device Owner, Android's standard installation confirmation remains mandatory.

The footer shows the currently installed GLoader version. **Update** checks the latest GitHub release, downloads the release APK when a newer version exists, and starts Android's normal package update confirmation.

The **Apps** tab shows installed packages visible to GLoader. User applications can be opened for Android's normal uninstall confirmation flow; protected system packages are shown as non-removable.

The **Cleanup** tab tracks packages that were successfully installed through GLoader. It can request removal of those tracked applications one by one, then optionally request removal of GLoader itself. Android asks for confirmation for every uninstall action. When GLoader is uninstalled, Android also removes GLoader's private app data.

Important limitation: a regular Android application cannot erase system Package Manager records, installation history, or system logs. Cleanup only covers tracked applications, GLoader's own uninstall request, and GLoader's private app data that Android deletes during uninstall.

## Builds and releases

GitHub Actions builds a minimized release APK whenever the workflow runs on the `main` branch and publishes a `1.X` release, where `X` is the automatically incremented Actions run number. The APK is signed with the repository debug release key for direct installation and update compatibility between Actions builds. Configure a private production signing key before distributing the application through an app store.

The project is intended to be built by the included GitHub Actions workflow. A local build, if required, needs JDK 17, Android SDK 35, and Gradle 8.9:

```sh
gradle assembleRelease
```

## Disclaimer

This project is unofficial and is not affiliated with or endorsed by Geely Holding Group. Installing third-party applications, enabling ADB, or modifying the settings or software of a vehicle head unit is done entirely at your own risk. These actions may cause instability, data loss, reduced security, loss of official updates, and full or partial refusal of warranty service for the vehicle and/or its head unit by the manufacturer or dealer. Review your warranty terms and applicable laws before using this software. The project authors accept no liability for damage to the vehicle, head unit, data, or third parties.

## License

See [LICENSE](LICENSE).
