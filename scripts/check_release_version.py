"""Verify tracked official catalogs before accepting a source release version."""

from __future__ import annotations

import argparse
import base64
import json
import subprocess
import tempfile
import textwrap
from datetime import datetime
from pathlib import Path
from typing import Any


OFFICIAL_STORE_ID = "paperreader.official.sources"
OFFICIAL_STORE_NAME = "PaperReader official sources"
OFFICIAL_STORE_WEBSITE = "https://github.com/ImAno177/PaperReader-sources"
MAX_VERSION_CODE = 2_100_000_000
MAX_ENVELOPE_BYTES = 2 * 1024 * 1024
MAX_PAYLOAD_BYTES = 1024 * 1024
MAX_PUBLIC_KEY_FILE_BYTES = 256
MAX_RELEASES = 200
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
INDEX_FIELDS = {
    "schemaVersion",
    "storeId",
    "displayName",
    "websiteUrl",
    "sequence",
    "generatedAt",
    "extensions",
}
RELEASE_FIELDS = {
    "kind",
    "packageName",
    "serviceClassName",
    "displayName",
    "providerId",
    "minimumRequestIntervalMillis",
    "sourceCapabilities",
    "sourceRoles",
    "sourceIdentifierTypes",
    "sourceSupportedSorts",
    "versionCode",
    "minimumVersionCode",
    "versionName",
    "signerSha256",
    "minimumHostApi",
    "maximumHostApi",
    "installUrl",
    "apkSha256",
    "apkSizeBytes",
    "license",
    "themeIds",
}


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def _reject_non_json_constant(value: str) -> None:
    raise ValueError(f"Invalid JSON constant: {value}")


def _parse_object(data: bytes, description: str) -> dict[str, Any]:
    try:
        result = json.loads(
            data.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid {description} JSON") from error
    if not isinstance(result, dict):
        raise ValueError(f"Invalid {description} JSON object")
    return result


def _read_regular_bounded(path: Path, maximum: int) -> bytes:
    try:
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Expected a regular file: {path}")
        with path.open("rb") as source:
            data = source.read(maximum + 1)
    except OSError as error:
        raise ValueError(f"Cannot read {path}") from error
    if len(data) > maximum:
        raise ValueError(f"File exceeds the {maximum}-byte limit: {path}")
    return data


def _decode_canonical_base64(
    value: Any,
    expected_length: int | None,
    description: str,
) -> bytes:
    if not isinstance(value, str):
        raise ValueError(f"Invalid {description} encoding")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ValueError(f"Invalid {description} encoding") from error
    if base64.b64encode(decoded).decode("ascii") != value:
        raise ValueError(f"Invalid {description} encoding")
    if expected_length is not None and len(decoded) != expected_length:
        raise ValueError(f"Invalid {description} encoding")
    return decoded


def _verify_ed25519(payload: bytes, signature: bytes, public_key: bytes) -> None:
    public_der = ED25519_SPKI_PREFIX + public_key
    public_pem = "-----BEGIN PUBLIC KEY-----\n"
    public_pem += textwrap.fill(base64.b64encode(public_der).decode("ascii"), 64)
    public_pem += "\n-----END PUBLIC KEY-----\n"
    try:
        with tempfile.TemporaryDirectory(prefix="paperreader-release-check-") as directory:
            temporary = Path(directory)
            payload_path = temporary / "payload.json"
            signature_path = temporary / "signature.bin"
            public_key_path = temporary / "public.pem"
            payload_path.write_bytes(payload)
            signature_path.write_bytes(signature)
            public_key_path.write_text(public_pem, encoding="ascii")
            result = subprocess.run(
                [
                    "openssl",
                    "pkeyutl",
                    "-verify",
                    "-rawin",
                    "-pubin",
                    "-inkey",
                    str(public_key_path),
                    "-sigfile",
                    str(signature_path),
                    "-in",
                    str(payload_path),
                ],
                check=False,
                capture_output=True,
            )
    except OSError as error:
        raise ValueError("Could not run OpenSSL to verify the official catalog") from error
    if result.returncode != 0:
        raise ValueError("Official catalog signature verification failed")


def _validate_index(index: dict[str, Any]) -> list[int]:
    if set(index) != INDEX_FIELDS:
        raise ValueError("Official catalog has an unexpected schema")
    if type(index["schemaVersion"]) is not int or index["schemaVersion"] != 1:
        raise ValueError("Unsupported official catalog schema")
    if index["storeId"] != OFFICIAL_STORE_ID:
        raise ValueError("Catalog does not belong to the official store")
    if index["displayName"] != OFFICIAL_STORE_NAME or index["websiteUrl"] != OFFICIAL_STORE_WEBSITE:
        raise ValueError("Official catalog identity changed")
    sequence = index["sequence"]
    if type(sequence) is not int or not 1 <= sequence <= MAX_VERSION_CODE:
        raise ValueError("Invalid official catalog sequence")
    generated_at = index["generatedAt"]
    if not isinstance(generated_at, str):
        raise ValueError("Invalid official catalog timestamp")
    try:
        parsed_date = datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("Invalid official catalog timestamp") from error
    if parsed_date.tzinfo is None:
        raise ValueError("Official catalog timestamp has no time zone")
    extensions = index["extensions"]
    if not isinstance(extensions, list) or len(extensions) > MAX_RELEASES:
        raise ValueError("Invalid official catalog releases")
    versions: list[int] = [sequence]
    packages: set[str] = set()
    for release in extensions:
        if not isinstance(release, dict) or set(release) != RELEASE_FIELDS:
            raise ValueError("Official catalog release has an unexpected schema")
        package_name = release["packageName"]
        if release["kind"] != "source" or not isinstance(package_name, str) or not package_name:
            raise ValueError("Invalid official catalog release")
        if package_name in packages:
            raise ValueError("Official catalog contains duplicate packages")
        packages.add(package_name)
        version = release["versionCode"]
        if type(version) is not int or not 1 <= version <= MAX_VERSION_CODE:
            raise ValueError("Invalid official catalog version code")
        versions.append(version)
    return versions


def verify_signed_index(envelope_path: Path, public_key_path: Path) -> list[int]:
    envelope_data = _read_regular_bounded(envelope_path, MAX_ENVELOPE_BYTES)
    envelope = _parse_object(envelope_data, "signed catalog envelope")
    if set(envelope) != {"payload", "signature"}:
        raise ValueError("Signed catalog envelope has an unexpected schema")
    payload = _decode_canonical_base64(envelope["payload"], None, "payload")
    if not payload or len(payload) > MAX_PAYLOAD_BYTES:
        raise ValueError("Official catalog payload exceeds its size limit")
    signature = _decode_canonical_base64(envelope["signature"], 64, "signature")
    key_data = _read_regular_bounded(public_key_path, MAX_PUBLIC_KEY_FILE_BYTES).strip()
    try:
        encoded_key = key_data.decode("ascii")
    except UnicodeDecodeError as error:
        raise ValueError("Invalid public key encoding") from error
    public_key = _decode_canonical_base64(encoded_key, 32, "public key")
    _verify_ed25519(payload, signature, public_key)
    return _validate_index(_parse_object(payload, "official catalog"))


def require_increasing_version(version_code: int, repository_root: Path) -> int:
    if type(version_code) is not int or not 1 <= version_code <= MAX_VERSION_CODE:
        raise ValueError("Release version code is outside Android's supported range")
    registry = repository_root / "registry"
    key_path = registry / "public-key-base64.txt"
    index_paths = (registry / "index.signed.json", registry / "v2" / "index.signed.json")
    existing_paths = [path for path in index_paths if path.exists()]
    if not existing_paths:
        raise ValueError("No tracked official catalog is available for version validation")
    published_versions: list[int] = []
    for path in existing_paths:
        published_versions.extend(verify_signed_index(path, key_path))
    maximum = max(published_versions)
    if version_code <= maximum:
        raise ValueError("version_code must exceed all verified published source and store versions")
    return maximum


def require_current_revision(expected_commit: str, repository_root: Path) -> None:
    revisions = subprocess.check_output(
        ["git", "-C", str(repository_root), "rev-parse", "HEAD", "refs/remotes/origin/main"],
        text=True,
    ).splitlines()
    if revisions != [expected_commit, expected_commit]:
        raise ValueError("Release checkout is stale; dispatch again from current main")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version_code", type=int)
    parser.add_argument("--expected-commit")
    arguments = parser.parse_args()
    try:
        if arguments.expected_commit is not None:
            require_current_revision(arguments.expected_commit, Path.cwd())
        maximum = require_increasing_version(arguments.version_code, Path.cwd())
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f"Release version code exceeds verified catalog maximum {maximum}.")


if __name__ == "__main__":
    main()
