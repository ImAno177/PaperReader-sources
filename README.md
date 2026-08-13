# PaperReader Sources

Official source extensions for [PaperReader](https://github.com/ImAno177/PaperReader).

Each provider is an independently installable Android APK. PaperReader discovers releases from a
signed store index, verifies the APK bytes, identity, and signer, then binds through the versioned
PaperReader AIDL contract. Provider code never runs inside the PaperReader process.

## Extensions

- **Semantic Scholar** — default relevance search and citation counts.
- **Crossref** — exact-DOI metadata enrichment; it is not a search or content source.
- **arXiv** — paper discovery, metadata, landing pages, and PDF manifestations.
- **Europe PMC** — biomedical discovery, identifiers, and licensed open-access manifestations.

## Build

Pass the path to a PaperReader checkout containing `:extension-api`:

```powershell
.\gradlew.bat -PpaperReaderSdkPath=D:\path\to\PaperReader testDebugUnitTest lintDebug assembleDebug
```

Release APKs are signed only in GitHub Actions. Private APK and store-index signing keys are never
stored in this repository.

## Security

The store index is Ed25519-signed. Every release records its package, version, host-contract range,
APK SHA-256, exact byte size, and signing-certificate fingerprint. Android still asks the user to
confirm a normal installation or update; the host rescans and trust-checks the package afterward.

## License

Copyright 2026 PaperReader contributors. Licensed under Apache-2.0.
