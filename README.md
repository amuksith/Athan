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


## Open Source Acknowledgments
This application is built with the following open-source frameworks:
- **Jetpack Compose & Material 3** (Google LLC • Apache License 2.0)
- **AndroidX Core & Lifecycle** (Google LLC • Apache License 2.0)
- **Kotlinx Coroutines** (JetBrains s.r.o. • Apache License 2.0)
- **Robolectric & Roborazzi** (Apache License 2.0)

