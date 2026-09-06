[![Android CI](https://github.com/ImAno177/PaperReader-sources/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ImAno177/PaperReader-sources/actions/workflows/android-ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

# PaperReader sources

Official source extensions for [PaperReader](https://github.com/ImAno177/PaperReader).

Status: separate Android APKs over the versioned PaperReader extension API. The host app never loads
provider code into its own process.

## Table of contents

- [About the project](#about-the-project)
- [Built with](#built-with)
- [Available sources](#available-sources)
- [Getting started](#getting-started)
- [Usage](#usage)
- [Release and registry](#release-and-registry)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [Security](#security)
- [License](#license)
- [Contact](#contact)
- [Acknowledgments](#acknowledgments)

## About the project

This repository owns the official provider implementations, their deterministic fixtures, release
APKs, and signed registry. Keeping providers separate lets them follow upstream API changes without
rebuilding the PaperReader host.

Each source runs in its own Android package and UID. Requests cross a bounded, cancellable AIDL
contract. The host owns identity, persistence, routing, trust, and UI; a source owns one upstream API,
its request policy, and its parser.

## Built with

| Area | Technology |
| --- | --- |
| Android | Kotlin, min SDK 28, target SDK 36, Android SDK Platform 37 |
| Contract | `dev.paperreader:extension-api:0.1.0` over versioned AIDL |
| Shared transport | Bounded HTTP, rate gating, cancellation, and caller verification |
| Build | Gradle wrapper, Java and Kotlin target 17, JDK 21 in CI |
| Release | Signed APKs, Ed25519 registry, SHA-256 and exact byte-size checks |

## Available sources

| Source | Role | Current behavior |
| --- | --- | --- |
| Semantic Scholar | Search engine | Preferred free-text relevance search and citation observations, with bounded anonymous-quota cooldowns |
| Crossref | Metadata engine | Exact normalized DOI metadata enrichment only |
| arXiv | Content source | Phrase-aware discovery, metadata, exact identifier/version lookup, landing pages, and PDF manifestations |
| Europe PMC | Content source | Biomedical discovery, DOI/PMID/PMCID lookup, and licensed open-access manifestations |

The host can use arXiv and Europe PMC as authoritative discovery fallbacks when they advertise the
matching capability. Crossref is not a fuzzy search provider.

## Getting started

### Prerequisites

- A PaperReader checkout containing the `:extension-api` module
- JDK 21
- Android SDK Platform 37 and Build-Tools 36.1.0

Place the PaperReader checkout in a directory named `PaperReader` under this repository, then run
the local gate:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

For a checkout at another location, set `PAPERREADER_SDK_PATH` to its absolute path and pass it to
Gradle:

```powershell
.\gradlew.bat `
  -PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH `
  testDebugUnitTest lintDebug assembleDebug
```

For a local runtime install, also pass the 64-character SHA-256 certificate digest of the PaperReader
host through `PAPERREADER_HOST_SIGNER_SHA256`:

```powershell
.\gradlew.bat `
  -PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH `
  -PpaperReaderHostSignerSha256=$env:PAPERREADER_HOST_SIGNER_SHA256 `
  assembleDebug
```

Each provider APK is written under that provider module's `build/outputs/apk/` directory. The
repository's CI and release workflows use the same local build contract, but live provider calls are
not deterministic tests.

## Usage

Install compatible signed APKs from the [repository releases](https://github.com/ImAno177/PaperReader-sources/releases)
or build a local debug set for a matching PaperReader host signer. PaperReader discovers the
extensions through its trusted store, verifies their package, version, API range, signer, and
artifact digest, then asks Android to confirm installation or update.

If a provider is unavailable, the host keeps other provider results and exposes a source-specific
failure or retry action. Disabling a source excludes it from new discovery without deleting saved
records or their provenance.

## Release and registry

The `registry/index.signed.json` file contains the signed release catalog. The envelope signs the exact
UTF-8 index bytes with Ed25519. Each installable entry binds the package, version code, host API range,
extension kind, APK URL, SHA-256, byte size, signer certificate, and provider capabilities.

Release APKs are signed only by GitHub Actions. Private APK and registry signing keys are never stored
in this repository. The registry tooling and the tracked public key are in [`scripts/`](scripts/) and
[`registry/`](registry/).

## Roadmap

- Keep each provider aligned with its official upstream API and documented rate policy.
- Add an official provider only when the host contract, routing policy, and provenance rules support it.
- Keep release metadata reproducible and independently verifiable.

## Contributing

Keep a pull request focused on one upstream API or shared transport concern. Add deterministic local
fixtures for parser and routing changes, document identifier and rate-limit behavior, and run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Do not commit API keys, downloaded papers, live-response snapshots containing personal data, signing
keys, or keystore material. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) and the host's
[extension guide](https://github.com/ImAno177/PaperReader/blob/main/docs/EXTENSIONS.md) before changing
the contract boundary.

## Security

Report vulnerabilities through the [PaperReader security policy](https://github.com/ImAno177/PaperReader/security/policy)
or the repository maintainers' private reporting channel. Do not publish exploit details in an issue.

The shared source service verifies the calling PaperReader package and signer on Binder entry points,
keeps network work bounded and cancellable, and never receives host database or filesystem paths.

## License

Licensed under the [Apache License 2.0](LICENSE). Provider APIs, paper records, and upstream content
remain subject to their own terms. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Contact

Use the [PaperReader-sources issue tracker](https://github.com/ImAno177/PaperReader-sources/issues) for
provider corrections, release questions, and focused contribution discussions.

## Acknowledgments

Provider behavior follows the official [Semantic Scholar](https://www.semanticscholar.org/product/api),
[Crossref](https://www.crossref.org/documentation/retrieve-metadata/rest-api/),
[arXiv](https://info.arxiv.org/help/api/user-manual.html), and
[Europe PMC](https://europepmc.org/RestfulWebService) APIs.
