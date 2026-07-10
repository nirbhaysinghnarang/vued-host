#!/usr/bin/env python3
"""
Prepare Android host update files for manual Supabase Storage upload.

Edit the constants below, then run:

    python3 scripts/publish_update.py

Then drag the opened folder's contents into the Supabase downloads/android-host/
folder.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from urllib.parse import quote


SUPABASE_URL = "https://eubvwnuocitdcctwqjox.supabase.co"
BUCKET = "downloads"
PREFIX = "android-host"
CHANNEL = "latest"

REPO_ROOT = Path(__file__).resolve().parents[1]
APK_PATH = REPO_ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AAPT_PATH = Path.home() / "Library/Android/sdk/build-tools/36.0.0/aapt"
OUTPUT_ROOT = REPO_ROOT / "dist/android-host"

PACKAGE_NAME = "com.nsn8.vued"
VERSION_NAME = "2.0"
VERSION_CODE = 2


def main() -> None:
    apk_path = APK_PATH
    if not apk_path.is_file():
        raise SystemExit(f"APK not found: {apk_path}")

    validate_apk_metadata(apk_path)
    validate_published_version()

    apk_name = f"vued-host-{VERSION_CODE}.apk"
    apk_output_path = OUTPUT_ROOT / str(VERSION_CODE) / apk_name
    manifest_output_path = OUTPUT_ROOT / CHANNEL / "manifest.json"
    apk_remote_path = f"{PREFIX}/{VERSION_CODE}/{apk_name}"

    apk_output_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_output_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk_path, apk_output_path)

    manifest = {
        "channel": CHANNEL,
        "packageName": PACKAGE_NAME,
        "versionName": VERSION_NAME,
        "versionCode": VERSION_CODE,
        "url": public_object_url(apk_remote_path),
        "sha256": sha256(apk_output_path),
        "sizeBytes": apk_output_path.stat().st_size,
    }
    manifest_output_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")

    print(f"Wrote APK: {apk_output_path}")
    print(f"Wrote manifest: {manifest_output_path}")
    print()
    print("Upload these into the Supabase Storage downloads bucket:")
    print(f"- {apk_output_path} -> {apk_remote_path}")
    print(f"- {manifest_output_path} -> {PREFIX}/{CHANNEL}/manifest.json")
    print()
    print(json.dumps(manifest, indent=2, sort_keys=True))
    print()
    open_output_folder(OUTPUT_ROOT)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_apk_metadata(apk_path: Path) -> None:
    if not AAPT_PATH.is_file():
        raise SystemExit(f"aapt not found: {AAPT_PATH}")
    output = subprocess.check_output(
        [str(AAPT_PATH), "dump", "badging", str(apk_path)],
        text=True,
    )
    package_line = output.splitlines()[0]
    actual_package = required_badging_value(package_line, "name")
    actual_version_code = int(required_badging_value(package_line, "versionCode"))
    actual_version_name = required_badging_value(package_line, "versionName")

    mismatches = []
    if actual_package != PACKAGE_NAME:
        mismatches.append(f"PACKAGE_NAME is {PACKAGE_NAME}, APK has {actual_package}")
    if actual_version_code != VERSION_CODE:
        mismatches.append(f"VERSION_CODE is {VERSION_CODE}, APK has {actual_version_code}")
    if actual_version_name != VERSION_NAME:
        mismatches.append(f"VERSION_NAME is {VERSION_NAME}, APK has {actual_version_name}")
    if mismatches:
        raise SystemExit("APK metadata does not match script constants:\n- " + "\n- ".join(mismatches))
    print(f"Validated APK: {actual_package} {actual_version_name} ({actual_version_code})")


def validate_published_version() -> None:
    manifest_url = public_object_url(f"{PREFIX}/{CHANNEL}/manifest.json")
    current = fetch_json(manifest_url)
    if current is None:
        print(f"No current manifest found at {manifest_url}")
        return

    current_package = current.get("packageName")
    current_version_code = int(current.get("versionCode", 0))
    current_version_name = str(current.get("versionName", ""))

    if current_package and current_package != PACKAGE_NAME:
        raise SystemExit(
            "Published manifest packageName does not match script constants:\n"
            f"- current manifest has {current_package}\n"
            f"- script has {PACKAGE_NAME}"
        )
    if VERSION_CODE <= current_version_code:
        raise SystemExit(
            "VERSION_CODE must be higher than the currently published manifest:\n"
            f"- current manifest: {current_version_name} ({current_version_code})\n"
            f"- script constants: {VERSION_NAME} ({VERSION_CODE})"
        )
    print(f"Current published version: {current_version_name} ({current_version_code})")


def fetch_json(url: str) -> dict | None:
    try:
        with urlopen(url, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        if error.code in {400, 404}:
            return None
        raise SystemExit(f"Could not fetch current manifest: HTTP {error.code}") from error
    except URLError as error:
        raise SystemExit(f"Could not fetch current manifest: {error.reason}") from error


def required_badging_value(line: str, key: str) -> str:
    match = re.search(rf"{re.escape(key)}='([^']+)'", line)
    if not match:
        raise SystemExit(f"Could not read {key} from APK badging: {line}")
    return match.group(1)


def public_object_url(remote_path: str) -> str:
    return (
        f"{SUPABASE_URL.rstrip('/')}/storage/v1/object/public/"
        f"{quote(BUCKET)}/{quote_path(remote_path)}"
    )


def quote_path(path: str) -> str:
    return "/".join(quote(part) for part in path.split("/"))


def open_output_folder(path: Path) -> None:
    subprocess.run(["open", str(path)], check=False)
    print(f"Opened folder: {path}")


if __name__ == "__main__":
    main()
