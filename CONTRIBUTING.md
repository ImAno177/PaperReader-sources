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

CI also runs the registry contract test, which needs no Android SDK:

```powershell
python scripts/test_registry_contract.py PaperReader
```

Do not commit API keys, signing material, downloaded papers, or live-response snapshots containing
personal data. A provider must use an official public endpoint and accurately declare its search,
metadata, content, identifier, and sort capabilities.
