#!/usr/bin/env python3
"""Check that official source APK metadata agrees with the pinned host SDK."""

from __future__ import annotations

import base64
import json
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build_registry import EXTENSION_API_VERSION, SOURCES, build_release_entry  # noqa: E402
from check_release_version import (  # noqa: E402
    require_increasing_version,
    verify_signed_index,
)


ANDROID = "http://schemas.android.com/apk/res/android"
SDK_ROOT = Path(sys.argv.pop(1)) if len(sys.argv) > 1 else ROOT / "PaperReader"


def balanced_call(source: str, call: str) -> str:
    start = source.index(call) + len(call)
    depth = 1
    quote = False
    escaped = False
    for index in range(start, len(source)):
        char = source[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quote = False
            continue
        if char == '"':
            quote = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[start:index]
    raise ValueError(f"Unclosed Kotlin call: {call}")


def split_arguments(body: str) -> list[str]:
    parts: list[str] = []
    start = 0
    depths = {"(": 0, "{": 0, "[": 0}
    opening_for = {")": "(", "}": "{", "]": "["}
    quote = False
    escaped = False
    for index, char in enumerate(body):
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quote = False
            continue
        if char == '"':
            quote = True
        elif char in depths:
            depths[char] += 1
        elif char in opening_for:
            depths[opening_for[char]] -= 1
        elif char == "," and not any(depths.values()):
            parts.append(body[start:index].strip())
            start = index + 1
    if body[start:].strip():
        parts.append(body[start:].strip())
    return parts


def parse_descriptor(source: str) -> dict[str, str]:
    body = balanced_call(source, "SourceExtensionDescriptor(")
    fields = {}
    for argument in split_arguments(body):
        name, value = argument.split("=", 1)
        fields[name.strip()] = value.strip()
    return fields


def parse_enum_wires(source: str) -> dict[str, dict[str, str]]:
    enums: dict[str, dict[str, str]] = {}
    for enum_name in ("SourceCapability", "SourceRole", "SourceIdentifierType", "SourceSearchSort"):
        match = re.search(rf"enum class {enum_name}\b.*?\{{(.*?)\n\}}", source, re.S)
        if match is None:
            raise AssertionError(f"Host SDK is missing {enum_name}")
        enums[enum_name] = dict(re.findall(r'([A-Z_]+)\("([^"\n]+)"\)', match.group(1)))
    return enums


class RegistryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sdk_root = SDK_ROOT.resolve()
        cls.contract_path = cls.sdk_root / "extension-api/src/main/kotlin/dev/paperreader/extensions/api/ExtensionContract.kt"
        cls.descriptor_path = cls.sdk_root / "extension-api/src/main/kotlin/dev/paperreader/extensions/api/SourceDescriptorContract.kt"
        cls.aidl_path = cls.sdk_root / "extension-api/src/main/aidl/dev/paperreader/extensions/api/IPaperSourceService.aidl"
        cls.readable_callback_path = cls.sdk_root / "extension-api/src/main/aidl/dev/paperreader/extensions/api/IPaperReadableDocumentCallback.aidl"
        cls.contract = cls.contract_path.read_text(encoding="utf-8")
        cls.descriptor_contract = cls.descriptor_path.read_text(encoding="utf-8")
        cls.aidl = cls.aidl_path.read_text(encoding="utf-8")
        cls.readable_callback = cls.readable_callback_path.read_text(encoding="utf-8")
        cls.enum_wires = parse_enum_wires(cls.descriptor_contract)

    def test_pinned_sdk_is_api_two_readable_document_contract(self) -> None:
        self.assertRegex(self.contract, r"const val API_VERSION:\s*Int\s*=\s*2\b")
        self.assertIn('READABLE_DOCUMENT("readable_document")', self.descriptor_contract)
        self.assertRegex(self.aidl, r"getReadableDocument\s*\(")
        self.assertRegex(self.aidl, r"cancel\s*\(String requestId\)")
        for callback in ("onChunk", "onComplete", "onFailure"):
            self.assertRegex(self.readable_callback, rf"\b{callback}\s*\(")
        common_build = (ROOT / "source-common/build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(common_build, r'dev\.paperreader:extension-api:0\.2\.0\b')

    def test_registry_identity_and_signing_key_match_the_pinned_host(self) -> None:
        host_build = (self.sdk_root / "app/build.gradle.kts").read_text(encoding="utf-8")
        host_store_id = re.search(
            r'"OFFICIAL_SOURCE_STORE_ID",\s*BuildConfigField\(\s*"String",\s*"([^"]+)"',
            host_build,
        )
        self.assertIsNotNone(host_store_id)
        self.assertEqual("paperreader.official.sources", host_store_id.group(1))
        host_index_url = re.search(
            r'"OFFICIAL_SOURCE_STORE_URL",\s*BuildConfigField\(\s*"String",\s*"([^"]+)"',
            host_build,
        )
        self.assertIsNotNone(host_index_url)
        self.assertEqual(
            "https://raw.githubusercontent.com/ImAno177/PaperReader-sources/main/registry/v2/index.signed.json",
            host_index_url.group(1),
        )
        host_public_key = re.search(
            r'"OFFICIAL_SOURCE_STORE_PUBLIC_KEY",\s*BuildConfigField\(\s*"String",\s*"([^"]+)"',
            host_build,
        )
        self.assertIsNotNone(host_public_key)
        registry_public_key = (ROOT / "registry/public-key-base64.txt").read_text(encoding="ascii").strip()
        self.assertEqual(registry_public_key, host_public_key.group(1))

    def test_manifest_descriptors_and_registry_metadata_are_identical(self) -> None:
        self.assertEqual(2, EXTENSION_API_VERSION)
        self.assertEqual(4, len(SOURCES))
        enum_types = {
            "sourceCapabilities": ("capabilities", "SourceCapability"),
            "sourceRoles": ("roles", "SourceRole"),
            "sourceIdentifierTypes": ("identifierLookupTypes", "SourceIdentifierType"),
            "sourceSupportedSorts": ("supportedSorts", "SourceSearchSort"),
        }
        for registry in SOURCES:
            module = registry["module"]
            generated = build_release_entry(
                registry,
                version_name="0.2.0",
                version_code=2,
                release_tag="v0.2.0",
                signer_sha256="ab" * 32,
                apk_data=b"fixture APK bytes",
            )
            self.assertEqual("source", generated["kind"], module)
            gradle = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            app_id = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)
            self.assertIsNotNone(app_id, module)
            self.assertEqual(registry["packageName"], app_id.group(1), module)

            manifest = ET.parse(ROOT / module / "src/main/AndroidManifest.xml").getroot()
            service = manifest.find("./application/service")
            self.assertIsNotNone(service, module)
            service_name = service.get(f"{{{ANDROID}}}name", "")
            full_service = service_name if "." not in service_name else registry["packageName"] + service_name
            self.assertEqual(registry["serviceClassName"], full_service, module)
            self.assertEqual("true", service.get(f"{{{ANDROID}}}exported"), module)
            self.assertEqual(":source", service.get(f"{{{ANDROID}}}process"), module)
            actions = {node.get(f"{{{ANDROID}}}name") for node in service.findall("./intent-filter/action")}
            self.assertEqual({"dev.paperreader.extensions.api.action.PAPER_SOURCE"}, actions, module)
            metadata = {
                item.get(f"{{{ANDROID}}}name"): item.get(f"{{{ANDROID}}}value")
                for item in service.findall("./meta-data")
            }
            self.assertEqual("2", metadata.get("dev.paperreader.extensions.API_VERSION"), module)
            self.assertEqual("source", metadata.get("dev.paperreader.extensions.KIND"), module)

            descriptor_path = ROOT / module / "src/main/kotlin" / Path(*registry["serviceClassName"].split("."))
            descriptor_path = descriptor_path.with_suffix(".kt")
            descriptor = parse_descriptor(descriptor_path.read_text(encoding="utf-8"))
            self.assertEqual("BuildConfig.APPLICATION_ID", descriptor.get("packageName"), module)
            for registry_key, (descriptor_key, enum_name) in enum_types.items():
                value = descriptor[descriptor_key]
                if value == "emptySet()":
                    parsed = []
                elif value == f"{enum_name}.entries.toSet()":
                    parsed = sorted(self.enum_wires[enum_name].values())
                else:
                    constants = re.findall(rf"{enum_name}\.([A-Z_]+)", value)
                    self.assertTrue(constants, f"{module}: no values for {descriptor_key}")
                    parsed = sorted(self.enum_wires[enum_name][constant] for constant in constants)
                self.assertEqual(sorted(generated[registry_key]), parsed, f"{module}: {registry_key}")

            self.assertEqual(registry["providerId"], re.fullmatch(r'"([^"]+)"', descriptor["providerId"]).group(1), module)
            self.assertEqual(registry["displayName"], re.fullmatch(r'"([^"]+)"', descriptor["displayName"]).group(1), module)
            interval = int(descriptor["minimumRequestIntervalMillis"].replace("_", ""))
            self.assertEqual(registry["minimumRequestIntervalMillis"], interval, module)
            self.assertEqual(2, generated["minimumHostApi"], module)
            self.assertEqual(2, generated["maximumHostApi"], module)


class ReleaseVersionPreflightTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.key_directory = tempfile.TemporaryDirectory(prefix="paperreader-release-test-key-")
        key_directory = Path(cls.key_directory.name)
        cls.private_key = key_directory / "private.pem"
        cls.public_key = key_directory / "public.pem"
        cls.public_key_base64 = key_directory / "public-key-base64.txt"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(cls.private_key)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            [
                "openssl",
                "pkey",
                "-in",
                str(cls.private_key),
                "-pubout",
                "-outform",
                "DER",
                "-out",
                str(cls.public_key),
            ],
            check=True,
            capture_output=True,
        )
        public_der = cls.public_key.read_bytes()
        cls.public_key_base64.write_text(base64.b64encode(public_der[-32:]).decode("ascii") + "\n")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.key_directory.cleanup()

    def signed_envelope(self, index: dict[str, object]) -> dict[str, str]:
        payload = json.dumps(index, separators=(",", ":"), sort_keys=True).encode("utf-8")
        with tempfile.TemporaryDirectory(prefix="paperreader-release-test-sign-") as directory:
            temporary = Path(directory)
            payload_path = temporary / "payload.json"
            signature_path = temporary / "signature.bin"
            payload_path.write_bytes(payload)
            subprocess.run(
                [
                    "openssl",
                    "pkeyutl",
                    "-sign",
                    "-rawin",
                    "-inkey",
                    str(self.private_key),
                    "-in",
                    str(payload_path),
                    "-out",
                    str(signature_path),
                ],
                check=True,
                capture_output=True,
            )
            return {
                "payload": base64.b64encode(payload).decode("ascii"),
                "signature": base64.b64encode(signature_path.read_bytes()).decode("ascii"),
            }

    @staticmethod
    def registry_index(sequence: int, version_codes: list[int]) -> dict[str, object]:
        releases = []
        for index, version_code in enumerate(version_codes):
            releases.append(
                {
                    "kind": "source",
                    "packageName": f"dev.paperreader.test.source{index}",
                    "serviceClassName": f"dev.paperreader.test.source{index}.Service",
                    "displayName": f"Source {index}",
                    "providerId": f"source{index}",
                    "minimumRequestIntervalMillis": 1000,
                    "sourceCapabilities": ["search"],
                    "sourceRoles": ["search_engine"],
                    "sourceIdentifierTypes": [],
                    "sourceSupportedSorts": ["relevance"],
                    "versionCode": version_code,
                    "minimumVersionCode": 1,
                    "versionName": "1.0.0",
                    "signerSha256": "ab" * 32,
                    "minimumHostApi": 2,
                    "maximumHostApi": 2,
                    "installUrl": f"https://example.org/source{index}.apk",
                    "apkSha256": "cd" * 32,
                    "apkSizeBytes": 1,
                    "license": "Apache-2.0",
                    "themeIds": [],
                },
            )
        return {
            "schemaVersion": 1,
            "storeId": "paperreader.official.sources",
            "displayName": "PaperReader official sources",
            "websiteUrl": "https://github.com/ImAno177/PaperReader-sources",
            "sequence": sequence,
            "generatedAt": "2026-01-01T00:00:00Z",
            "extensions": releases,
        }

    def write_envelope(self, path: Path, envelope: dict[str, str]) -> None:
        path.write_text(json.dumps(envelope, separators=(",", ":")), encoding="utf-8")

    def test_tracked_official_catalogs_have_valid_signatures_and_schema(self) -> None:
        paths = [ROOT / "registry/index.signed.json", ROOT / "registry/v2/index.signed.json"]
        existing = [path for path in paths if path.exists()]
        self.assertTrue(existing)
        for path in existing:
            versions = verify_signed_index(path, ROOT / "registry/public-key-base64.txt")
            self.assertTrue(versions)

    def test_tampered_payload_and_signature_fail_real_ed25519_verification(self) -> None:
        with tempfile.TemporaryDirectory(prefix="paperreader-release-test-envelope-") as directory:
            envelope_path = Path(directory) / "index.signed.json"
            valid = self.signed_envelope(self.registry_index(5, [5]))
            self.write_envelope(envelope_path, valid)
            self.assertEqual([5, 5], verify_signed_index(envelope_path, self.public_key_base64))

            tampered_payload = dict(valid)
            payload = json.loads(base64.b64decode(tampered_payload["payload"]))
            payload["sequence"] = 6
            tampered_payload["payload"] = base64.b64encode(
                json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"),
            ).decode("ascii")
            self.write_envelope(envelope_path, tampered_payload)
            with self.assertRaisesRegex(ValueError, "signature verification failed"):
                verify_signed_index(envelope_path, self.public_key_base64)

            tampered_signature = dict(valid)
            signature = bytearray(base64.b64decode(tampered_signature["signature"]))
            signature[0] ^= 1
            tampered_signature["signature"] = base64.b64encode(signature).decode("ascii")
            self.write_envelope(envelope_path, tampered_signature)
            with self.assertRaisesRegex(ValueError, "signature verification failed"):
                verify_signed_index(envelope_path, self.public_key_base64)

    def test_valid_signature_does_not_allow_an_unknown_catalog_schema(self) -> None:
        with tempfile.TemporaryDirectory(prefix="paperreader-release-test-schema-") as directory:
            envelope_path = Path(directory) / "index.signed.json"
            index = self.registry_index(5, [5])
            index["unexpected"] = "field"
            self.write_envelope(envelope_path, self.signed_envelope(index))
            with self.assertRaisesRegex(ValueError, "unexpected schema"):
                verify_signed_index(envelope_path, self.public_key_base64)

    def test_equal_and_lower_release_versions_are_rejected_against_verified_catalogs(self) -> None:
        with tempfile.TemporaryDirectory(prefix="paperreader-release-test-repo-") as directory:
            root = Path(directory)
            registry = root / "registry"
            (registry / "v2").mkdir(parents=True)
            (registry / "public-key-base64.txt").write_bytes(self.public_key_base64.read_bytes())
            self.write_envelope(
                registry / "index.signed.json",
                self.signed_envelope(self.registry_index(40, [41])),
            )
            self.write_envelope(
                registry / "v2/index.signed.json",
                self.signed_envelope(self.registry_index(50, [52])),
            )

            self.assertEqual(52, require_increasing_version(53, root))
            with self.assertRaisesRegex(ValueError, "must exceed"):
                require_increasing_version(52, root)
            with self.assertRaisesRegex(ValueError, "must exceed"):
                require_increasing_version(51, root)


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]])
