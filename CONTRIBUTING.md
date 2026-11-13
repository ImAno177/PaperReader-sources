# Contributing

Open a focused pull request for one provider or shared transport concern. Explain the provider's
documented rate limits and identifier semantics, and add deterministic fixtures.

## Local gate

Place the PaperReader checkout in a directory named `PaperReader` under this repository, or pass
another absolute path through `PAPERREADER_SDK_PATH`. Then run the same Gradle tasks as CI, with the
checkout passed to the build explicitly:

```powershell
.\gradlew.bat -PpaperReaderSdkPath=PaperReader testDebugUnitTest lintDebug assembleDebug
```

For a checkout at another location:

```powershell
.\gradlew.bat -PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH testDebugUnitTest lintDebug assembleDebug
```

Without the property, `settings.gradle.kts` falls back to `PaperReader` and skips the SDK build
when that directory is missing, so the build fails to resolve `:extension-api`.

The CI gate also runs the registry contract test, which needs no Android SDK:

```powershell
python scripts/test_registry_contract.py PaperReader
```

The registry tests and the release checks in `scripts/check_release_version.py` need Python 3 and
`openssl` on `PATH`.

## Continuous integration

CI runs on demand, not on pushes or pull requests. Start it for a branch with:

```powershell
gh workflow run android-ci.yml --repo ImAno177/PaperReader-sources --ref <branch>
```

Add `-f paperreader_sdk_ref=<40-character SHA>` to test a host SDK commit other than the
`PAPERREADER_SDK_REF` repository variable.

A pull request merges after the local gate above passes on its head commit and a code review finds no
open problems. No GitHub status check is required. Release workflows run the Android gate, CodeQL, and
the release compatibility checks on the release commit before they publish anything.

Do not commit API keys, signing material, downloaded papers, or live-response snapshots containing
personal data. A provider must use an official public endpoint and accurately declare its search,
metadata, content, identifier, and sort capabilities.
