# Contributing

Open a focused pull request for one provider or shared transport concern. Explain the provider's
documented rate limits and identifier semantics, add deterministic fixtures, and run the complete
local gate:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Do not commit API keys, signing material, downloaded papers, or live-response snapshots containing
personal data. A provider must use an official public endpoint and accurately declare its search,
metadata, content, identifier, and sort capabilities.

