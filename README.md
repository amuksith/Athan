# Athan

Privacy-first, 100% offline Android prayer-time and Athan application.

## Overview

Athan is designed from the ground up to respect user privacy and operate completely offline. It provides precise Islamic prayer timings, Qibla compass orientation, and audio prayer calls without requiring network access, user accounts, or analytics tracking.

## Core Features

- **Astronomical Calculation**: High-precision prayer times computed locally using standard calculation methods (Muslim World League, ISNA, Egyptian General Authority of Survey, Umm Al-Qura University Makkah, University of Islamic Sciences Karachi, Dubai, Kuwait, Qatar, MUIS Singapore, Institute of Geophysics University of Tehran) with adjustable angles and prayer time adjustments.
- **Offline Qibla Compass**: Great-circle Qibla direction and bearing calculation with device sensor smoothing and numerical bounds protection.
- **Bundled Offline City Database**: Comprehensive global city database with latitude, longitude, and elevation bundled directly in the application APK.
- **Offline Timezone Resolution**: Canonical IANA timezone mapping for all bundled locations ensuring proper Daylight Saving Time (DST) handling without external APIs.
- **Local Audio Playback**: Authentic Athan audio playback with active foreground service, notification actions, and race-free MediaPlayer lifecycle management.
- **Reliable Exact Alarm Scheduling**: 3-day rolling exact alarms via Android AlarmManager with boot, clock change, and timezone change receivers.

## Privacy Guarantee

- **Zero Network Permissions**: The application explicitly declares NO `android.permission.INTERNET` (`tools:node="remove"`).
- **Zero Telemetry & Analytics**: No tracking SDKs, crash reporters, or advertising frameworks.
- **Zero Cloud Dependencies**: No Firebase, Google Play Services Auth, or remote server dependencies.
- **100% Local Storage**: All preferences, adjustments, and settings are stored locally on the device using Android SharedPreferences.


## License

Athan is licensed under the GNU General Public License v3.0.

See the [LICENSE](LICENSE) file for the complete license terms.

## Open Source Acknowledgments

Athan uses third-party open-source libraries distributed under their respective licenses.

The applicable third-party licenses and notices are documented in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

Athan itself remains licensed separately under the GNU General Public License v3.0.

## Developer

Developed by **Muksith**.

Source code: https://github.com/amuksith/Athan

