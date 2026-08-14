# FocusLock

![FocusLock mascot](design/focuslock-mascot-source.png)

FocusLock is a personal Android focus companion that puts a little space between you and distracting apps. It blocks Instagram, YouTube, and Reddit with a playful lock-screen mascot, timed focus zones, and short reflection prompts.

## What it does

- Uses Android Accessibility Service to notice when a selected distracting app is opened.
- Shows a firm lock screen from **8:30 PM to 10:00 AM** and a softer reflection screen from **10:00 AM to 3:00 PM**. From **3:00 PM to 8:30 PM**, those apps are freely available.
- Lets you pause FocusLock for 15 minutes after entering your local password.
- Includes a payment-app compatibility flow for Google Pay, PhonePe, Paytm, and BHIM.
- Keeps its data on-device. It has no account, analytics, server, or network permission.

## Important payment-app note

Some banking and payment apps intentionally refuse to run while *any* Accessibility Service or "Display over other apps" permission is active. Pausing FocusLock stops its blocking behaviour, but the strictest apps can still detect those Android-level permissions.

Use **Payment compatibility** in FocusLock before opening one of those apps. It turns off FocusLock's accessibility service and opens Android's overlay-permission screen so you can disable that permission too. Relaunch the payment app afterward, then turn FocusLock back on when you are finished. This is an Android security policy, so no blocker app can reliably bypass it.

## Run locally

1. Clone the project and open it in Android Studio.
2. Let Gradle sync, then run the `app` configuration on an Android device or emulator.
3. In the app, set a local password and grant:
   - **App detection** (Accessibility Service)
   - **Display over other apps**
4. For more reliable blocking, exclude FocusLock from battery optimization in your phone settings.

On Windows, a debug build and unit tests can be run with:

```powershell
.\gradlew.bat test assembleDebug
```

## Privacy and limitations

- FocusLock requests only the accessibility and overlay capabilities needed to present its block screens. Its accessibility configuration does **not** retrieve on-screen content.
- The local password is stored with Android encrypted preferences. Backups are disabled for this app.
- The app list and schedule are currently built in, rather than editable from the interface.
- Device makers and sensitive payment apps can apply additional restrictions. Test the behaviour on your own device before relying on it.

## Testing status

`test assembleDebug` passes locally, and the debug build has been installed on a physical Android device. Instrumented Android tests are included, but downloading their AndroidX test dependencies is currently blocked on this development machine by a Java certificate-trust issue; that is still worth resolving before a production release.

## License

This project is available under the [MIT License](LICENSE).
