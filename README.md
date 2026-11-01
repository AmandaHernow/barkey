# BarKey

![BarKey logo with “BY AMANDA HERNOW”](artwork/BarKey_README_Banner.png)

**A free barcode and QR-code scanning keyboard for Android, for private personal use.**

BarKey replaces the on-screen keyboard with a camera scanner. It reads barcodes on the device and enters the result directly into the active text field. Scanning works offline. BarKey does not use the clipboard, advertisements, analytics or tracking.

## What it does

- Scans QR codes and supported one- and two-dimensional barcode formats with the rear camera.
- Inserts a scanned value at the cursor in the active text field.
- Offers **Fast** and **Accurate** scan modes. Accurate makes a more thorough attempt and can take longer.
- Lets you tap the camera preview to request focus and exposure at that point. A torch control appears when the camera supports flash.
- Provides **Preview**, **Focus** and **Single Scan** options. Preview lets you choose from up to eight detected values. Focus limits scanning to a movable, resizable area. Single Scan allows one successful insertion per recognised text field. Focus works with either option; Preview and Single Scan cannot be used together.
- Includes Undo for up to 20 recent scans or Space insertions when the current text field can be checked safely. Undo history stays in memory and is lost when BarKey restarts. Undoing a Single Scan in an otherwise empty field makes that field available for another scan.
- Includes Backspace, Space, Left, Right, Tab, Enter, keyboard switching and hide controls. The receiving app determines how navigation and editor actions behave.
- Supports portrait and landscape layouts, system light and dark appearance, and a resizable docked keyboard.

BarKey works on Android phones and tablets. It can also run on shared or managed Android devices, including devices enrolled in Microsoft Intune, but managed deployment is not the main focus of the app. This does not permit commercial or workplace use.

## First use

1. Open BarKey and read the personal-use agreement. If you select BarKey as your keyboard before opening the launcher app, the agreement appears when you first use the keyboard.
2. Agree to the terms, then follow the setup guide to enable and select BarKey in Android settings.
3. Allow camera access when Android asks.
4. Focus a text field, select BarKey from Android’s keyboard picker, and point the rear camera at a barcode.

Android controls which keyboard is enabled and selected. BarKey does not change these settings for you.

## Privacy

Camera frames are decoded locally and scanned values are entered directly into the active text field. BarKey does not need internet access and does not send camera frames, scanned values or surrounding text anywhere. It does not keep a barcode history or store scans. Temporary scan results and guarded Undo data are held in memory only.

BarKey requests camera access so it can scan. No clipboard permission is needed.

For details, read the [Privacy Policy](https://amandahernow.github.io/barkey/privacy-policy.html) or the in-keyboard **About & Help** panel.

## Intended use and licence

BarKey is free to use for private, personal purposes. Company or workplace use, commercial activity, paid redistribution, or inclusion in a paid product or service requires Amanda Hernow’s prior written approval. Companies must contact Amanda before installing or testing BarKey for work or evaluation. There is no company trial or free scan allowance.

Contact [barkey.support@hernow.net](mailto:barkey.support@hernow.net) to request commercial permission or discuss a commercial version.

The source code is available under the [PolyForm Noncommercial License 1.0.0](LICENSE). The separate in-app agreement explains the personal-use terms for the BarKey app. Earlier copies released under Apache License 2.0 remain under their original terms; this licence change does not revoke permissions already granted.

## More information

- [BarKey website](https://amandahernow.github.io/barkey/)
- [About BarKey](https://amandahernow.github.io/barkey/about.html)
- [Privacy Policy](https://amandahernow.github.io/barkey/privacy-policy.html)
- [Support](mailto:barkey.support@hernow.net)

**Copyright © 2026 Amanda Hernow.**
