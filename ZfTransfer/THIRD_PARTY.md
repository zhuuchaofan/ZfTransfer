# Third-party sources and modifications

## Nikon Pull — GPL-3.0

Source: https://github.com/noisetta/Nikon-Pull
Pinned revision: 60f2e3ee428202435e953b7cb4bbf0553b19028a

The `io.github.noisetta.nikonpull.ptp` source files and the three original protocol test files are copied from this project. The repository's GPL-3.0 license is preserved as `LICENSE` and bundled in the app. Original authors retain copyright in their work.

Modifications dated 2026-10-27: optional Zf smart-device mode; reference fixed client GUID; GetDeviceInfo before OpenSession at transaction 0; enumerate all storage through GetObjectHandles without GetStorageIDs; diagnostic callback; Zf dialect regression test and truncated-transfer test. MainActivity, Android packaging and setup instructions are new work distributed under GPL-3.0.

## ZPill — MIT

Source: https://github.com/jaminxx-aha/ZPill
Pinned revision: 76d4c8ed6bce0da1a962ffc7fc9e7ccc8e1f3afd

The Zf mode follows its published PTP/IP protocol observations and fixed GUID layout. The MIT license and copyright notice are preserved under `licenses/ZPill-MIT.txt`, also bundled in the APK.

Relevant evidence: `lib/core/ptpip/README.md` and `tools/ptpip_probe/probe.py`. Author reports Zf smart-device Wi-Fi JPEG GetObject / GetThumb and all-storage enumeration tested. This is upstream evidence, not a hardware test of this build.

## Camera_Bridge — reference only, no source copied

https://github.com/HuiLiYiImpl/Camera_Bridge
Revision inspected: ad9c801dd64ebaca9a45c64b1346e3b5e6448350

Its README reports Xiaomi 14 + Nikon Zf Wi-Fi operation. No explicit repository LICENSE was found in this checkout, so its implementation has not been copied into this deliverable. That report does not establish this build's device compatibility.

## Build/runtime components

Kotlin standard library (JetBrains), Apache-2.0, is bundled in the APK; its license/copyright files from the Gradle distribution are supplied under `licenses/`. Gradle wrapper retains upstream Apache-2.0 notices. Android SDK/build tools and JUnit are build/test tools, not bundled in the app. All included source is provided with the test APK.
