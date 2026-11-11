Gallery X 3.5.0 - owner project

- secure/gallery-x-release.jks      : release signing key (RSA 4096)
- secure/keystore.properties        : its passwords (read automatically by app/build.gradle)

Keep the whole "secure" folder private. Anyone holding it can sign apps as you.
Back it up somewhere safe and offline: losing it means you can never ship an update
that installs over this version.

The app checks its own signing certificate (AppIntegrity.java) and closes if it was
signed with any other key. Certificate SHA-256:
76:48:A4:53:AB:9C:45:62:54:CA:3F:F3:46:30:51:34:00:E0:2C:54:8B:D4:E4:4D:B4:81:33:03:BB:B4:C6:D3

Upgrading from 3.4.x: the old key was replaced, so 3.5.0 does not install over it.
Before uninstalling 3.4.x, move everything OUT of its vault (it is deleted with the app).
