# AirPods Control for Samsung — rootless v0.4

v0.4 is a diagnostics/stability release for Samsung S24 Ultra + AirPods Pro 2.

Changes:
- Rejects generic Apple BLE frames such as `12 02 00 01`; they no longer trigger an AirPods popup.
- A result is promoted to an AirPods candidate only when the Apple manufacturer frame has the expected proximity structure.
- Captures the complete BLE ScanRecord plus Apple manufacturer bytes for diagnostics.
- Shows Apple frame type, byte length, candidate/rejected status, and rejected-frame count.
- Uses A2DP/HEADSET/ACL together as the effective Bluetooth connection state.
- Scanner updates preserve profile/UUID state instead of overwriting it.
- Existing AirPods app icon and 7 languages remain: Turkish, English, German, French, Spanish, Italian, Hindi.

This build does not fake unsupported Apple controls. ANC/AACP work remains separated from BLE monitoring.
