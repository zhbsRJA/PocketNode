# Privacy Policy — PocketNode

**Last updated: 2026-09-19**

## Summary

PocketNode processes everything on your device. It does not collect, transmit,
or share any personal data. There is no analytics, no advertising, and no
third-party tracking of any kind.

## What the app does with your data

**Conversations.** Messages you type and the model's replies are held in memory
while the app is running. They are not written to disk by default, and they are
never sent anywhere.

**Model files.** Downloaded model files are stored on your device's storage.
They contain no user data.

**Local logs.** The app writes a diagnostic log file to its own private storage
so that crashes and errors can be investigated. This file stays on your device.
It contains no conversation content.

## Network access

The app makes network requests in exactly two situations:

1. **Downloading a model file**, when you explicitly start a download. This
   request goes to the model host configured in the app, and carries no
   information about you beyond what any HTTP request carries.

2. **Serving the local relay**, when you explicitly turn it on. This listens on
   your local network only. Requests from clients on your WiFi are handled
   on-device; nothing is forwarded to any external server.

The app does not contact any server on its own initiative, and it does not send
usage statistics, crash reports, or device identifiers anywhere.

## Permissions and why they are requested

| Permission | Purpose |
|---|---|
| `INTERNET` | Downloading model files, serving the local relay |
| `ACCESS_NETWORK_STATE` | Checking connectivity before starting a download |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keeping downloads and the relay alive while the app is in the background |
| `POST_NOTIFICATIONS` | Showing download progress |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Asking the system not to suspend the app's background work |

## Data sharing

None. The app does not share data with third parties because it does not
collect any.

## Data retention and deletion

All data — models, settings, logs — lives on your device. Uninstalling the app
removes it. There is nothing to delete on any server, because nothing is stored
on any server.

## Children

The app is a developer tool and is not directed at children.

## Changes to this policy

Any changes will be published at this URL with an updated date.

## Contact

For questions about this policy, open an issue at the repository hosting this
document.
