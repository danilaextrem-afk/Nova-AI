# NOVA AI Android — development slice

A real Kotlin/Jetpack Compose Android application with Retrofit calls to the NOVA AI Fastify server.
This is **not** a full consumer release. No simulated payments, ad rewards, chat AI or art generation are presented as working.

## Running
- Android Gradle Plugin 8.11.1; Gradle 8.13; JDK 17; Android SDK 36.
- From `android/`, run `gradle :app:assembleDebug :app:testDebugUnitTest` with Gradle 8.13 installed.
- GitHub Actions uploads a debug APK artifact only after a successful build.

## Backend connection
1. Run the backend on an HTTPS endpoint using the repository's Docker Compose (local dev setup requires HTTPS reverse proxy for device use).
2. Open the app and replace `https://api.example.invalid/` with your real **HTTPS** API base URL.
3. Register, log in, create characters, browse approved characters or read your wallet.
4. There are no real AI calls, billing purchases or rewarded ads in this first Android slice.

## Security and release limits
- Authentication token is held in memory; refresh-token rotation and encrypted persistence are future work.
- Only HTTPS is accepted; cleartext traffic is disabled.
- Never put provider API keys in the APK.
- Google Play Billing, AdMob Rewarded SSV, AI endpoints and full navigation remain future work.
- Release APK/AAB must not be distributed until Play signing, store configuration, tests and legal reviews are completed.
