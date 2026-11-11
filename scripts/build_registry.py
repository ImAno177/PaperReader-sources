#!/usr/bin/env python3
"""Build and Ed25519-sign the official PaperReader source store envelope."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path


EXTENSION_API_VERSION = 2


SOURCES = (
    {
        "module": "source-semanticscholar",
        "packageName": "dev.paperreader.extensions.sources.semanticscholar",
        "serviceClassName": "dev.paperreader.extensions.sources.semanticscholar.SemanticScholarService",
        "displayName": "Semantic Scholar",
        "providerId": "semanticscholar",
        "minimumRequestIntervalMillis": 1000,
        "sourceCapabilities": ["search", "details"],
        "sourceRoles": ["search_engine"],
        "sourceIdentifierTypes": [],
        "sourceSupportedSorts": ["relevance"],
    },
    {
        "module": "source-crossref",
        "packageName": "dev.paperreader.extensions.sources.crossref",
        "serviceClassName": "dev.paperreader.extensions.sources.crossref.CrossrefService",
        "displayName": "Crossref",
        "providerId": "crossref",
        "minimumRequestIntervalMillis": 1000,
        "sourceCapabilities": ["details"],
        "sourceRoles": ["metadata_engine"],
        "sourceIdentifierTypes": ["doi"],
        "sourceSupportedSorts": ["relevance"],
    },
    {
        "module": "source-arxiv",
        "packageName": "dev.paperreader.extensions.sources.arxiv",
        "serviceClassName": "dev.paperreader.extensions.sources.arxiv.ArxivService",
        "displayName": "arXiv",
        "providerId": "arxiv",
        "minimumRequestIntervalMillis": 3000,
        "sourceCapabilities": ["search", "details", "pdf_link", "readable_document"],
        "sourceRoles": ["content_source"],
        "sourceIdentifierTypes": ["arxiv"],
        "sourceSupportedSorts": ["relevance", "newest", "oldest"],
    },
    {
        "module": "source-europepmc",
        "packageName": "dev.paperreader.extensions.sources.europepmc",
        "serviceClassName": "dev.paperreader.extensions.sources.europepmc.EuropePmcService",
        "displayName": "Europe PMC",
        "providerId": "europepmc",
        "minimumRequestIntervalMillis": 1000,
        "sourceCapabilities": ["search", "details", "pdf_link"],
        "sourceRoles": ["content_source"],
        "sourceIdentifierTypes": ["doi", "pmid", "pmcid"],
        "sourceSupportedSorts": ["relevance", "newest"],
    },
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--signer-sha256", required=True)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--root", default=Path.cwd(), type=Path)
    return parser.parse_args()


def build_release_entry(
    source: dict[str, object],
    *,
    version_name: str,
    version_code: int,
    release_tag: str,
    signer_sha256: str,
    apk_data: bytes,
) -> dict[str, object]:
    return {
        "kind": "source",
        **{key: value for key, value in source.items() if key != "module"},
        "versionCode": version_code,
        "minimumVersionCode": 1,
        "versionName": version_name,
        "signerSha256": signer_sha256.lower(),
        "minimumHostApi": EXTENSION_API_VERSION,
        "maximumHostApi": EXTENSION_API_VERSION,
        "installUrl": (
            "https://github.com/ImAno177/PaperReader-sources/releases/download/"
            f"{release_tag}/{source['module']}.apk"
        ),
        "apkSha256": hashlib.sha256(apk_data).hexdigest(),
        "apkSizeBytes": len(apk_data),
        "license": "Apache-2.0",
        "themeIds": [],
    }


def main() -> None:
    args = arguments()
    if args.version_code < 1 or re.fullmatch(r"[0-9a-fA-F]{64}", args.signer_sha256) is None:
        raise SystemExit("Invalid version or signer fingerprint")
    releases = []
    for source in SOURCES:
        apk = args.root / source["module"] / "build/outputs/apk/release" / f'{source["module"]}-release.apk'
        data = apk.read_bytes()
        releases.append(
            build_release_entry(
                source,
                version_name=args.version_name,
                version_code=args.version_code,
                release_tag=args.release_tag,
                signer_sha256=args.signer_sha256,
                apk_data=data,
            )
        )
    index = {
        "schemaVersion": 1,
        "storeId": "paperreader.official.sources",
        "displayName": "PaperReader official sources",
        "websiteUrl": "https://github.com/ImAno177/PaperReader-sources",
        "sequence": args.version_code,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "extensions": releases,
    }
    payload = json.dumps(index, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode()
    with tempfile.TemporaryDirectory() as temporary:
        payload_path = Path(temporary) / "payload.json"
        signature_path = Path(temporary) / "signature.bin"
        payload_path.write_bytes(payload)
        subprocess.run(
            [
                "openssl",
                "pkeyutl",
                "-sign",
                "-rawin",
                "-inkey",
                str(args.private_key),
                "-in",
                str(payload_path),
                "-out",
                str(signature_path),
            ],
            check=True,
        )
        signature = signature_path.read_bytes()
    if len(signature) != 64:
        raise SystemExit("Unexpected Ed25519 signature length")
    envelope = {
        "payload": base64.b64encode(payload).decode(),
        "signature": base64.b64encode(signature).decode(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(envelope, separators=(",", ":")) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
