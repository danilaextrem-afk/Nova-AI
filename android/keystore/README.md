# Test signing key — NOT for production

`nova-test.jks` signs the preview APK published by CI (`nova-preview` release).
Its password (`nova-test-only`) is public, so:

- every preview build has the same signature and installs as an update;
- anyone could sign an APK with it — install previews only from this repository's Releases page;
- never use it as a Google Play upload key or for a production release.

Production builds use the `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` repository secrets.
Users of a test build must uninstall it once before installing a production-signed build.
