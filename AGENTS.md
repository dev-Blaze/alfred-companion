# Release workflow

- Complete each implementation phase with verification, a commit, a push to origin, and a GitHub release before starting the next phase.
- Increment versionName and versionCode for each new release; never replace an existing release with different code.
- Use tags `v<version>` and watch APK asset names `alfred-companion-v<version>.apk`, never `app-release.apk`.
- Include phase-specific release notes and distinguish completed checks from device/backend checks that could not be run.
- Phone APK assets use `alfred-v<version>.apk`. Phone and watch release APKs must have matching signing certificates.
