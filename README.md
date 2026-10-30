# BarKey

![BarKey logo with “BY AMANDA HERNOW”](artwork/BarKey_README_Banner.png)

**Free, privacy-focused barcode and QR scanner keyboard for Android**

**Developer:** Amanda Hernow

**Version:** 1.0.1

**Repository:** [github.com/AmandaHernow/barkey](https://github.com/AmandaHernow/barkey)

## Website and search

- [Live BarKey website](https://amandahernow.github.io/barkey/)
- [About BarKey](https://amandahernow.github.io/barkey/about.html)
- [XML sitemap](https://amandahernow.github.io/barkey/sitemap.xml)
- [HTML sitemap](https://amandahernow.github.io/barkey/sitemap.html)
- [Privacy Policy](https://amandahernow.github.io/barkey/privacy-policy.html)

**Support:** [barkey.support@hernow.net](mailto:barkey.support@hernow.net)

**BarKey — Developed by Amanda Hernow** is a free, open-source Android barcode and QR scanner keyboard designed to work offline, without advertisements or tracking.

This repository prepares the personal, public Google Play version of BarKey. The application ID remains `net.hernow.barkey`.

BarKey is an Android Input Method Editor (IME) that replaces the usual on-screen keyboard with a rear-camera scanning interface. It decodes barcode and QR code values on the device and inserts them directly into the currently focused text field. This can simplify inventory management, production work, warehouse operations, enterprise forms and other workflows where operators repeatedly enter scanned values.

Version 1.0 is the first BarKey-branded development version. Project-owner testing has verified camera scanning, barcode insertion, Undo, repeating Backspace and keyboard layout on a physical Android tablet. The tablet model and OS version are not recorded here, and broader device, editor and accessibility compatibility checks remain ongoing.

## Features

- Scan QR codes and supported one-dimensional and two-dimensional barcode formats using the device's rear camera.
- Insert decoded text directly into the focused field through Android's `InputConnection`; no clipboard transfer is used.
- Decode frames locally and offline with ZXing-C++ Android 3.1.1, with ZXing Core as a compatibility fallback. Configured formats include QR Code, Micro QR, rMQR, Data Matrix, Aztec, PDF417, MicroPDF417, UPC-A, UPC-E, EAN-8, EAN-13, Code 39, Code 93, Code 128, ITF, Codabar, MaxiCode, RSS-14/DataBar and RSS Expanded/DataBar Expanded, plus GS1 DataBar Omni, Stacked, Stacked Omni, Limited and Expanded Stacked.
- Choose Fast mode for normal, clearly printed barcodes or Accurate mode for difficult, angled, damaged or inverted labels. Fast is the default; Accurate may take longer.
- Tap anywhere on the camera preview to request autofocus and exposure metering at that point. A torch control appears in the preview only when the bound camera supports flash.
- Follow Android's system light and dark appearance.
- Provide portrait and landscape layouts for phones and tablets, with a docked, non-fullscreen IME whose measured content keeps the editing controls visible above system navigation insets.
- Resize the keyboard continuously by dragging the discreet handle above the live camera preview; extra window height enlarges the preview while editing buttons keep their normal size.
- Include Undo, Backspace, Space, Left, Right, Tab and Enter editing controls, plus controls to switch keyboards or hide BarKey.
- Include an in-keyboard About & Help panel with usage instructions, privacy and permission explanations, control descriptions, open-source attribution and the installed app version.
- Include a launcher setup guide for enabling and selecting BarKey through Android's own settings and keyboard picker.
- Request camera permission when needed, explain permission and camera errors in the keyboard, and release/restart camera resources with the keyboard lifecycle.
- Suppress an identical scan repeated within the current input session's one-second duplicate window.
- Support the Android managed configuration `scan_suffix` with `none`, `enter` and `tab` options; the default is `none`.
- Provide a capability suitable for private enterprise deployment and management through Microsoft Intune. The deployment workflow remains to be validated.

## Optional extended scanning functions

The `extended-functions` development branch is developing three optional controls beneath the scanner preview. They are not included in a public release:

- **Preview** shows distinct detected values so you can choose which barcode to insert, useful when several codes are close together. Automatic insertion pauses while Preview is on.
- **Focus** adds a movable square that restricts decoding to the selected area, useful for labels with neighbouring codes. It is separate from tapping the preview to request camera autofocus. Focus uses the Java compatibility decoder for cropped frames, so speed and supported formats may differ from ordinary native-first scanning.
- **Single Scan** accepts one successfully inserted barcode per recognisable text field and allows scanning again after moving to another field. Android does not always provide enough field identity information, particularly in some browser and WebView forms, so this cannot be guaranteed for every app. It also works with a selected Preview suggestion.

Preview and Focus can be combined to narrow the scan area and then choose a detected value. Single Scan can be added to prevent another insertion into that field. Fast and Accurate remain independent scanning modes.

The core scanner and keyboard workflow has been verified on a physical Android tablet. Camera behaviour, field navigation, accessibility, compatibility across other devices and enterprise policy interactions still need broader validation.

The GitHub Pages website source is in [`docs/`](docs/).

Tap the circled **?** beside scanner status to open About & Help. The panel scrolls within the docked keyboard, leaves the focused text field unchanged and pauses camera use until it is closed. Close it with the **Close** button or Android Back; scanning then resumes when camera permission is available.

Drag the 40dp grab mark at the top of BarKey upward to make the docked keyboard taller or downward to make it shorter. The handle has a 48dp touch area, and the selected height stays in memory for the current IME service lifetime. BarKey clamps it to usable display bounds and keeps some application content visible when possible. The preview changes size; the editing controls do not stretch.

## Privacy and security

BarKey is free to use. It contains no advertisements, analytics, telemetry, user tracking, accounts or registration. The source manifest requests camera access and removes Internet and network-state permissions. Camera frames and barcode decoding are handled locally. Scans are inserted into the destination field without using the clipboard, and the app does not maintain barcode history or persist scanned values.

A decoded value exists temporarily in memory while it is processed and delivered. Preview holds only distinct values currently detected so they can be selected, then expires each suggestion shortly after it is no longer detected; it does not keep a scan history. Duplicate suppression retains only the most recently accepted value during the active input session, for comparison within a one-second window. The guarded one-level Undo feature may temporarily retain the most recent inserted value and bounded surrounding cursor context in memory. That context is cleared when the relevant field/session changes or the keyboard is hidden or switched away from. These values are not written to logs or persistent storage. This description reflects the app implementation and is not a broader security certification.

## Installation and first use

BarKey is being prepared for a public Google Play release. There is not yet a published Play listing or release APK. When a signed release is made available:

1. Install the signed APK on the Android device. Updating an existing installation requires a package signed with the same signing key as that installation.
2. Open BarKey from the launcher and follow its setup guide. Android requires you to enable and select the keyboard manually; the guide opens Android's settings and picker without changing those settings for you.
3. If prompted, allow camera access. If permission was denied permanently, use the displayed button to open BarKey's camera permission settings.
4. Focus a text field in any app and select BarKey with the Android keyboard picker. Point the rear camera at a supported barcode; the value is inserted at the cursor.
5. Tap **Switch Keyboard** to return to another enabled keyboard, or **Hide Keyboard** to dismiss the IME.

BarKey is not Direct Boot aware. Android's warning that an enabled IME may be unavailable until the device is unlocked after a restart is expected. For this intended workflow, unlock managed tablets before scanning; BarKey does not bypass lock-screen security.

## Keyboard controls

| Control | Behaviour |
| --- | --- |
| Undo | Attempts to undo the latest safely verified BarKey scan or Space insertion. It is enabled only while the active editor connection, input session, cursor/selection and bounded surrounding text still match. Private/password editors and selected-text replacements cannot be undone. Original selected text is not restored. |
| Backspace | Tap to delete the selection or the Unicode code point immediately before the cursor. Hold to repeat at Android's standard key repeat timing. |
| Space | Inserts one space at the current cursor/selection. |
| Left / Right | Requests cursor movement in the current field. Selection and field-boundary behaviour depends on the receiving editor. |
| Tab | Requests `IME_ACTION_NEXT` only when the field explicitly advertises `IME_ACTION_NEXT` or `IME_FLAG_NAVIGATE_NEXT`. Otherwise it is a no-op: BarKey sends no Tab key, Enter, Done or form submission, and does not hide the keyboard. The receiving app controls focus traversal and may handle an advertised action in its own way. |
| Enter | Performs a recognised editor action (such as Next, Done, Go, Search or Send) when appropriate; otherwise it inserts a newline in supported multiline fields or sends an Enter key event. The receiving application controls the result. |
| Switch Keyboard | Returns to a previously used enabled keyboard where Android supports that, or opens the system keyboard picker. It does not assume a particular keyboard app. |
| Hide Keyboard | Hides BarKey. |

The scanner status row contains the Fast/Accurate mode button immediately to the left of **?**. The button label always shows the active mode. Mode selection is held only for the current keyboard service lifetime and resets to Fast when that service is recreated. Tap the preview to focus at that location. On devices with flash, tap the flashlight control at the preview's upper-left to switch the torch on or off; BarKey turns it off when the keyboard closes or Help is opened.

Android `InputConnection` support varies among native fields, Chrome forms and WebView/custom forms. In particular, editor actions, surrounding-text queries, key events and field navigation may be unavailable or interpreted differently by the host. BarKey uses bounded surrounding-text queries and safe fallbacks, but Undo remains unavailable when an editor cannot provide enough reliable context, including a reliable collapsed-selection result. When selection cannot be verified, Backspace falls back to standard Delete key events so the receiving editor handles selections; event acceptance alone does not verify deletion or create Undo. Held repeat stops when those events are refused or context is unchanged or unavailable. A configured Enter or Tab `scan_suffix` may submit a form or move focus; Undo cannot reverse that action and is available only while the same field and inserted scan remain safely verifiable. Tab intentionally does nothing unless the field explicitly advertises forward navigation; Android does not let an IME universally inspect or control another app's focus traversal. The managed `scan_suffix` is the only action triggered automatically after a scan; interactive Enter and Tab controls do not run automatically. Undo context is cleared when another editing action changes the cursor or field, when the keyboard is hidden or switched, or when the input session ends.

## Android compatibility

The current app configuration is **minimum SDK 24**, **target SDK 37** and **compile SDK 37**. These values describe the source configuration, not a guarantee that every device running those API levels has been tested. The project owner reports verification of camera scanning, barcode insertion, Undo, Backspace repetition and keyboard layout on a physical Android tablet; the model and OS version have not been recorded in this repository. The launcher guide and keyboard support phone and tablet configurations in portrait and landscape and follow Android's system light/dark appearance. Additional device and host-app testing remains outstanding. The project uses Android Gradle Plugin 9.5.0 and Gradle 9.8.2 with Kotlin Gradle Plugin 2.4.21. Gradle 9.8.2 meets AGP 9.5.0's minimum Gradle requirement of 9.6.0. However, Kotlin's [published full-support range](https://kotlinlang.org/docs/gradle-configure-project.html) for KGP 2.4.21 currently ends at Gradle 9.7.0 and AGP 9.3.1, so the selected versions are outside that range. Kotlin says later Gradle and AGP releases may still work, but can produce deprecation warnings or have features that are not fully supported. Run the local build and tests after toolchain upgrades and review compatibility warnings.

The IME requests a docked window and disables fullscreen extract mode. Its measured minimum includes the handle, essential controls, footer and a small preview. The user-resizable maximum targets 75% of the display area above the top system inset; the keyboard window retains bottom gravity and a zero Y offset. The IME window extends to its platform-defined bottom boundary while root padding reserves the navigation inset for keyboard content, so that inset is counted once. On exceptionally short displays, the preview shrinks first; if the display still cannot fit all controls, the controls area becomes scrollable with its scrollbar visible while the footer remains visible. BarKey cannot dictate how a third-party host resizes or pans its form above an IME. Host soft-input handling can affect what remains visible. Camera availability in an Android Studio emulator depends on its virtual camera configuration. A virtual rear camera may be absent or behave differently from physical hardware; additional devices and Lenovo-specific behaviour still need validation. Emulator success does not validate Intune policy.

### Android Studio emulator acceptance checklist

These additional checks require an Android Studio emulator or device and have not been run in Codespaces. The project-owner physical tablet verification covers camera scanning, insertion, Undo, repeating Backspace and the keyboard layout; the specific device and OS are not documented here.

1. **Docking:** Open Chrome's address field with BarKey selected. Confirm the keyboard touches the bottom of the available IME area and no webpage strip appears beneath its footer.
2. **Resize upward:** Drag the top handle upward. Confirm only the keyboard top moves, the bottom stays fixed, and the live preview grows into the added height.
3. **Resize downward:** Drag down. Confirm the preview shrinks first and editing controls and footer stay accessible.
4. **Signature:** At default tablet height, confirm the exact text `BarKey · Developed by Amanda Hernow · 2026 · Version 1.0` is centred below Switch Keyboard / Hide Keyboard.
5. **Chrome Tab without NEXT:** Focus a Chrome field without advertised Next navigation and press Tab. Confirm no key injection, submission, focus change or BarKey dismissal.
6. **Native form with NEXT:** Focus the first of two native fields advertising NEXT. Confirm BarKey requests NEXT once and the host may move focus to field two.
7. **DONE field:** Focus a field advertising DONE without NEXT and press Tab. Confirm no Done action or injected key event.
8. **Camera lifecycle:** Resize repeatedly while scanning. Confirm the preview resizes without unnecessary CameraX restart.
9. **Help and orientation:** Open/close Help, rotate the tablet and return to Chrome. Confirm bottom docking, sensible clamping and a visible footer.
10. **System navigation:** Repeat with gesture navigation and three-button navigation. Confirm no controls or footer are obscured.

Pure Kotlin geometry tests verify the fixed-bottom calculation, not Android WindowManager's actual placement. The docking and navigation checks above must be observed in the emulator.

The ZXing-C++ 3.1.1 Android AAR includes native libraries for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`; 64-bit ARM and x86 devices are covered by `arm64-v8a` and `x86_64`. No app-level ABI filter is configured. The upstream Android wrapper enables 16 KB maximum page-size alignment for native binaries.

## Developer build instructions

The checked-in project currently specifies:

| Component | Version/configuration |
| --- | --- |
| Android Gradle Plugin | 9.5.0 |
| Gradle wrapper | 9.8.2 |
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

Use an Android Studio version that supports AGP 9.5.0. Install the required Android SDK platform and Build Tools in SDK Manager, select JDK 21 for Gradle, open the repository root, and allow Android Studio to sync the project. Because the current AGP and Gradle versions are newer than the Kotlin plugin's published full-support range, verify sync and builds in the local environment and review compatibility warnings. Do not commit machine-specific `local.properties` or signing credentials.

On Windows, build a development APK from PowerShell in the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. A debug APK is for development and testing; it is not a production release package. Production distribution requires an intentional release-signing and key-management process. No release signing key is included in this repository.

## Enterprise deployment

BarKey is intended for private deployment on company-managed Android Enterprise devices, including through Microsoft Intune. It is not currently listed on Google Play. Administrators can evaluate distributing it as a private line-of-business app or through an appropriate private Managed Google Play workflow. The app provides the Android managed configuration restriction `scan_suffix`, with values `none`, `enter` and `tab`; unset or invalid values resolve to `none`.

Deployment must account for the Android camera permission and the requirement to enable/select BarKey as an input method. Whether a particular Intune policy, Android Enterprise mode, OEM restriction or kiosk configuration can grant camera access and permit IME selection must be verified in that environment. Intune packaging, managed configuration delivery, permission policy, signing, kiosk operation and updates have not yet been validated end to end.

## Project status and roadmap

BarKey is under active development and is being prepared for a public Google Play release. Version 1.0 is the initial BarKey-branded development version. Physical-tablet verification is complete for the core scanner and keyboard workflow; remaining priorities include:

- Test the IME, permissions, layouts and form controls in Android Studio emulators.
- Expand camera scanning and lifecycle testing across additional physical Android phones and tablets, including the Lenovo Tab P12 target.
- Validate Chrome and WebView form behaviour, accessibility and compatibility with required enterprise apps.
- Validate private Intune/Android Enterprise deployment and managed `scan_suffix` configuration.
- Establish production signing and secure release-key management.
- Complete Play Console declarations, store listing and release review before publication; no listing or release APK currently exists.

## Open Source & Credits

BarKey is developed by Amanda Hernow and licensed under the [Apache License 2.0](LICENSE). Primary local barcode decoding is provided by [ZXing-C++ Android 3.1.1](https://github.com/zxing-cpp/zxing-cpp); [ZXing Core 3.5.4](https://github.com/zxing/zxing) is the Java decoder fallback. Both projects use Apache License 2.0. [AndroidX CameraX](https://github.com/androidx/androidx) provides camera capture and preview; AndroidX Core and Lifecycle provide Android application support. [Google Guava 33.7.2-android](https://github.com/google/guava) is a CameraX support dependency pinned by BarKey. These runtime libraries use Apache License 2.0; CameraX camera-core artifact metadata also declares BSD-3-Clause material. The test-only dependency [JUnit 4.13.2](https://github.com/junit-team/junit4) uses [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html). Project and applicable licence links are collected in the project [NOTICE](NOTICE).

## Copyright and licence

**Copyright © 2026 Amanda Hernow.**

Licensed under the [Apache License, Version 2.0](LICENSE).

## Developer

**Amanda Hernow** — [GitHub](https://github.com/AmandaHernow)
