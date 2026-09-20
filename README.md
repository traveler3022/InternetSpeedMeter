# Internet Speed Meter (Android)

An Android application that displays real-time download and upload speeds in the status bar and tracks daily mobile and Wi-Fi data usage.

## Features
- **Real-time Speed in Status Bar:** Custom dynamic notification icon showing current network speed.
- **Detailed Notification:** Separate download and upload speeds, along with daily Mobile and Wi-Fi data usage breakdown.
- **Battery Friendly:** Automatically pauses background polling when the screen is turned off (`ACTION_SCREEN_OFF`) and resumes when turned on.
- **Daily Usage Tracking:** Persisted with Room Database and SharedPreferences.
- **Auto-start on Boot:** Automatically launches the monitoring service when the device reboots.
- **Modern Android Support:** Compatible with Android 14+ (API 34) with proper foreground service types and notification permissions.

## Repository
- **GitHub:** [https://github.com/traveler3022/InternetSpeedMeter](https://github.com/traveler3022/InternetSpeedMeter)
