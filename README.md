# BarKey

**Free, privacy-focused barcode and QR scanner keyboard for Android**

**Developer:** Amanda Hernow

**Version:** 1.0

**Repository:** [github.com/AmandaHernow/barkey](https://github.com/AmandaHernow/barkey)

BarKey is a free, open-source Android barcode and QR scanner keyboard designed to work offline, without advertisements or tracking.

This README documents the substantially modified BarKey project derived from the 2020 Android Barcode Keyboard.

BarKey is an Android Input Method Editor (IME) that replaces the usual on-screen keyboard with a rear-camera scanning interface. It decodes barcode and QR code values on the device and inserts them directly into the currently focused text field. This can simplify inventory management, production work, warehouse operations, enterprise forms and other workflows where operators repeatedly enter scanned values.

BarKey is an independent, actively maintained application developed from Raphael Michel's open-source Android Barcode Keyboard. It has undergone substantial redesign and further development. Version 1.0 is the first BarKey-branded development version; it is not a claim of production readiness. The application has not yet completed the planned emulator, physical-device or enterprise deployment validation.

## Features

- Scan QR codes and supported one-dimensional and two-dimensional barcode formats using the device's rear camera.
- Insert decoded text directly into the focused field through Android's `InputConnection`; no clipboard transfer is used.
- Decode frames locally and offline with ZXing-C++ Android 3.1.1, with ZXing Core as a compatibility fallback. Configured formats include QR Code, Micro QR, rMQR, Data Matrix, Aztec, PDF417, MicroPDF417, UPC-A, UPC-E, EAN-8, EAN-13, Code 39, Code 93, Code 128, ITF, Codabar, MaxiCode, RSS-14/DataBar and RSS Expanded/DataBar Expanded, plus GS1 DataBar Omni, Stacked, Stacked Omni, Limited and Expanded Stacked.
- Choose Fast mode for normal, clearly printed barcodes or Accurate mode for difficult, angled, damaged or inverted labels. Fast is the default; Accurate may take longer.
- Tap anywhere on the camera preview to request autofocus and exposure metering at that point. A torch control appears in the preview only when the bound camera supports flash.
- Follow Android's system light and dark appearance.
- Provide portrait and landscape layouts for phones and tablets, with a docked, non-fullscreen IME and a camera preview sized to the available keyboard window.
- Include Undo, Backspace, Space, Left, Right, Tab and Enter editing controls, plus controls to switch keyboards or hide BarKey.
- Include an in-keyboard About & Help panel with usage instructions, privacy and permission explanations, control descriptions, open-source attribution and the installed app version.
- Request camera permission when needed, explain permission and camera errors in the keyboard, and release/restart camera resources with the keyboard lifecycle.
- Suppress an identical scan repeated within the current input session's one-second duplicate window.
- Support the Android managed configuration `scan_suffix` with `none`, `enter` and `tab` options; the default is `none`.
- Provide a capability suitable for private enterprise deployment and management through Microsoft Intune. The deployment workflow remains to be validated.

These features describe the implementation. Camera behaviour, field navigation, accessibility, device compatibility and enterprise policy interactions still require testing on emulators and physical production devices.

Tap the circled **?** beside scanner status to open About & Help. The panel scrolls within the docked keyboard, leaves the focused text field unchanged and pauses camera use until it is closed. Close it with the **Close** button or Android Back; scanning then resumes when camera permission is available.

## Privacy and security

BarKey is free to use. It contains no advertisements, analytics, telemetry, user tracking, accounts or registration. The app manifest requests camera access and does not request Internet access. Camera frames and barcode decoding are handled locally. Scans are inserted into the destination field without using the clipboard, and the app does not maintain barcode history or persist scanned values.

A decoded value exists temporarily in memory while it is processed and delivered. Duplicate suppression retains only the most recently accepted value during the active input session, for comparison within a one-second window. The guarded one-level Undo feature may temporarily retain the most recent inserted value and bounded surrounding cursor context in memory. That context is cleared when the relevant field/session changes or the keyboard is hidden or switched away from. These values are not written to logs or persistent storage. This description reflects the app implementation and is not a broader security certification.

## Installation and first use

There is currently no BarKey release APK or Google Play listing. When a signed APK is made available through a trusted distribution channel:

1. Install the signed APK on the Android device. Updating an existing installation requires a package signed with the same signing key as that installation.
2. Open Android **Settings → System → Keyboard → On-screen keyboard** (the exact menu labels vary by Android version and device), then enable **BarKey**. Android may display its standard warning about input methods; only enable a keyboard you trust.
3. Focus an editable text field and use Android's keyboard picker/globe control to select **BarKey**.
4. If prompted, tap **Enable Camera** and allow camera access. If permission was denied permanently, use the displayed button to open BarKey's camera permission settings.
5. Point the rear camera at a supported barcode. The decoded value is inserted at the current cursor position in the focused field.
6. Tap **Switch Keyboard** to return to another enabled keyboard, or **Hide Keyboard** to dismiss the IME.

BarKey is not Direct Boot aware. Android's warning that an enabled IME may be unavailable until the device is unlocked after a restart is expected. For this intended workflow, unlock managed tablets before scanning; BarKey does not bypass lock-screen security.

## Keyboard controls

| Control | Behaviour |
| --- | --- |
| Undo | Attempts to undo the most recent BarKey insertion only while the active editor connection, input session, cursor/selection and surrounding text can be verified safely. It is unavailable for private/password fields, selected-text replacements and editors that cannot provide reliable context. If the scan or cursor context changed, undo is rejected; original selected text is not restored. |
| Backspace | Deletes the selected text or the Unicode code point immediately before the cursor, when the editor supports the operation. |
| Space | Inserts one space at the current cursor/selection. |
| Left / Right | Requests cursor movement in the current field. Selection and field-boundary behaviour depends on the receiving editor. |
| Tab | Requests navigation to the next field using the editor's advertised action or an appropriate key-event fallback. It does not deliberately insert a literal tab. The receiving application must support the navigation. |
| Enter | Performs a recognised editor action (such as Next, Done, Go, Search or Send) when appropriate; otherwise it inserts a newline in supported multiline fields or sends an Enter key event. The receiving application controls the result. |
| Switch Keyboard | Returns to a previously used enabled keyboard where Android supports that, or opens the system keyboard picker. It does not assume a particular keyboard app. |
| Hide Keyboard | Hides BarKey. |

The scanner status row contains the Fast/Accurate mode button immediately to the left of **?**. The button label always shows the active mode. Mode selection is held only for the current keyboard service lifetime and resets to Fast when that service is recreated. Tap the preview to focus at that location. On devices with flash, tap the flashlight control at the preview's upper-left to switch the torch on or off; BarKey turns it off when the keyboard closes or Help is opened.

Android `InputConnection` support varies among native fields, Chrome forms and WebView/custom forms. In particular, editor actions, surrounding-text queries, key events and field navigation may be unavailable or interpreted differently by the host. BarKey avoids speculative focus changes and disables unsafe Undo, but no universal compatibility with every form can be promised. The managed `scan_suffix` is the only action triggered automatically after a scan; interactive Enter and Tab controls do not run automatically.

## Android compatibility

The current app configuration is **minimum SDK 24**, **target SDK 37** and **compile SDK 37**. These values describe the source configuration, not a guarantee that every device running those API levels has been tested. The layout supports phone and tablet configurations in portrait and landscape and follows Android's system light/dark appearance; planned device testing remains outstanding. The configured AGP version is 9.4.1 while the Kotlin Gradle Plugin compatibility table currently lists full support through AGP 9.3.1 for KGP 2.4.21. Validate Gradle sync/build in the stated Android Studio environment and reconcile versions if the toolchain reports incompatibility; this migration does not change dependencies or build-tool versions.

The IME requests a docked window and disables fullscreen extract mode. Its own height and preview adapt to available window dimensions, but BarKey cannot dictate how a third-party host resizes or pans its form above an IME. Host soft-input and inset handling can affect what remains visible. Camera availability in an Android Studio emulator depends on its virtual camera configuration. A virtual rear camera may be absent or behave differently from physical hardware; camera decoding and Lenovo-specific behaviour must be validated on a configured emulator and target tablet. For an initial emulator check, build/install the debug APK from Android Studio, enable BarKey in the emulator's keyboard settings, focus a native text field, select BarKey with the IME picker, and exercise the permission flow and controls. Configure a virtual rear-camera scene in the AVD settings to test preview/decoding; otherwise, an unavailable-camera status is expected. Emulator success does not validate physical camera behaviour or Intune policy.

The ZXing-C++ 3.1.1 Android AAR includes native libraries for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`; 64-bit ARM and x86 devices are covered by `arm64-v8a` and `x86_64`. No app-level ABI filter is configured. The upstream Android wrapper enables 16 KB maximum page-size alignment for native binaries.

## Developer build instructions

The checked-in project currently specifies:

| Component | Version/configuration |
| --- | --- |
| Android Gradle Plugin | 9.4.1 |
| Gradle wrapper | 9.7.0 |
| Kotlin Gradle Plugin | 2.4.21 |
| Gradle JDK | 21 (selected by the checked-in Gradle daemon JVM configuration) |
| Java/Kotlin bytecode target | 17 |
| Android SDK | Platform 37; Build Tools 36.0.0 |
| Minimum / target SDK | 24 / 37 |
| CameraX | 1.6.2 |
| AndroidX Lifecycle / Core | 2.11.0 / 1.19.1 |
| ZXing Core | 3.5.4 |
| ZXing-C++ Android | 3.1.1 |
| Guava Android | 33.7.2-android |
| JUnit | 4.13.2 (test dependency) |

Use an Android Studio version that supports AGP 9.4.1 (the development environment for this project is Android Studio Rabbit 1). Install the required Android SDK platform and Build Tools in SDK Manager, select JDK 21 for Gradle, open the repository root, and allow Android Studio to sync the project. Do not commit machine-specific `local.properties` or signing credentials.

On Windows, build a development APK from PowerShell in the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. A debug APK is for development and testing; it is not a production release package. Production distribution requires an intentional release-signing and key-management process. No release signing key is included in this repository.

## Enterprise deployment

BarKey is intended for private deployment on company-managed Android Enterprise devices, including through Microsoft Intune. It is not currently listed on Google Play. Administrators can evaluate distributing it as a private line-of-business app or through an appropriate private Managed Google Play workflow. The app provides the Android managed configuration restriction `scan_suffix`, with values `none`, `enter` and `tab`; unset or invalid values resolve to `none`.

Deployment must account for the Android camera permission and the requirement to enable/select BarKey as an input method. Whether a particular Intune policy, Android Enterprise mode, OEM restriction or kiosk configuration can grant camera access and permit IME selection must be verified in that environment. Intune packaging, managed configuration delivery, permission policy, signing, kiosk operation and updates have not yet been validated end to end.

## Project status and roadmap

BarKey is under active development. Version 1.0 is the initial BarKey-branded development version, not necessarily a production-ready release. Priorities include:

- Test the IME, permissions, layouts and form controls in Android Studio emulators.
- Test camera scanning and lifecycle behaviour on physical Android phones and tablets, especially the Lenovo Tab P12 production target.
- Validate Chrome and WebView form behaviour, accessibility and compatibility with required enterprise apps.
- Validate private Intune/Android Enterprise deployment and managed `scan_suffix` configuration.
- Establish production signing and secure release-key management.
- Consider Google Play publication when the project is ready; no listing or release APK currently exists.

## Open-source origins and acknowledgements

BarKey was developed from Raphael Michel's [Android Barcode Keyboard](https://github.com/raphaelm/android-barcode-keyboard), originally released under the Apache License 2.0 in 2020. BarKey has since been substantially redesigned and further developed. The original author is credited for the original work; BarKey's later modifications are attributed separately to Amanda Hernow.

The scanner uses [ZXing-C++ Android 3.1.1](https://github.com/zxing-cpp/zxing-cpp) as its primary local decoder and retains [ZXing Core](https://github.com/zxing/zxing) as a compatibility fallback. It offers Fast and Accurate scanning modes, tap-to-focus and an optional torch on devices with flash hardware. Both decoders run on-device; no scanned data is sent over the network. AndroidX and CameraX provide Android camera/UI functionality, and Guava is a dependency. These components are distributed under Apache License 2.0. JUnit 4.13.2 is used only as a test dependency and is licensed under [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html). See the project [NOTICE](NOTICE) for attribution details and [LICENSE](LICENSE) for the licence applying to BarKey's project distribution.

## Copyright and licence

**Copyright © 2026 Amanda Hernow.**

**Portions Copyright © 2020 Raphael Michel.**

Licensed under the [Apache License, Version 2.0](LICENSE).

BarKey is based on [Android Barcode Keyboard](https://github.com/raphaelm/android-barcode-keyboard) by Raphael Michel (2020), with substantial modifications and further development by Amanda Hernow (2026).

Barcode decoding uses [ZXing](https://github.com/zxing/zxing) and [ZXing-C++](https://github.com/zxing-cpp/zxing-cpp), both licensed under Apache License 2.0.

## Developer

**Amanda Hernow** — [GitHub](https://github.com/AmandaHernow)
