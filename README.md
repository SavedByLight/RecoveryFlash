# RecoveryFlash

A rooted Android GUI app for flashing `recovery.img`, `boot.img`, and
`vendor_boot.img` directly to their partitions via `dd`, without needing
fastboot/bootloader access.

## Requirements

- A rooted Android device (Magisk or similar) with a root manager granting
  this app `su` access
- Android Studio (or command-line Gradle + Android SDK) to build

## Build

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

(If `gradlew` isn't executable: `chmod +x gradlew` first. If you don't have
the wrapper jar, open the project in Android Studio once and it will
generate it, or run `gradle wrapper` if you have Gradle installed locally.)

## What it does

- Detects root access on launch
- Lists the actual partitions present on `/dev/block/by-name/` on your
  device (naming varies a lot by manufacturer/chipset — this app does not
  hardcode partition names)
- Lets you pick a `.img` file and flash it to a selected partition with
  `dd`, after a confirmation dialog
- Warns if the selected image is larger than the target partition
- Lets you back up the current partition contents to a file before
  overwriting it
- Reboot to recovery / bootloader shortcuts
- Basic device info screen (model, board, A/B slot info)
- Live progress log (root checks, partition discovery, dd output, errors),
  viewable in a popup window from either screen via the "View Log" button,
  with Copy and Clear actions

## Safety notes — read before using

- **This can hard-brick your device.** Writing the wrong image to the
  wrong partition, or an image built for a different device/chipset, can
  make the device unbootable with no software recovery path.
- Always verify the image is built specifically for your exact device
  model and current firmware/Android version before flashing.
- Always use the backup feature to save the current partition before
  overwriting it, so you have something to restore from.
- On A/B (seamless update) devices, this app resolves `recovery`/`boot`/
  `vendor_boot` to the currently active slot (`_a` or `_b`) automatically,
  but double-check the partition list shown in the spinner to be sure
  you're targeting the right one.
- This app is unofficial and not affiliated with or endorsed by the TWRP
  project.

## Project layout

```
RecoveryFlash/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/recoveryflash/app/
│       │   ├── MainActivity.kt         — flash screen: UI + flow control
│       │   ├── RebootBackupActivity.kt — backup/reboot screen
│       │   ├── LogDialog.kt            — popup window that renders the shared log
│       │   ├── AppLog.kt               — in-memory, timestamped progress log
│       │   ├── RootUtils.kt            — su/root command execution
│       │   ├── PartitionUtils.kt       — partition discovery & A/B slot resolution
│       │   └── FlashUtils.kt           — dd-based flash/backup logic
│       └── res/
│           └── layout/
│               ├── activity_main.xml
│               ├── activity_reboot_backup.xml
│               └── dialog_log.xml      — layout for the popup log window
├── build.gradle
└── settings.gradle
```
