#!/usr/bin/env python3
"""
Prepare Android host update files for manual Supabase Storage upload.

Run with an explicit APK version:

    python3 scripts/publish_update.py --version-name 3.0 --version-code 3

Or bump app/build.gradle.kts, build the APK, and publish the bumped version:

    python3 scripts/publish_update.py --bump

Then drag the opened folder's contents into the Supabase downloads/android-host/
folder.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from urllib.parse import quote


DEFAULT_SUPABASE_URL = "https://eubvwnuocitdcctwqjox.supabase.co"
BUCKET = "downloads"
PREFIX = "android-host"
CHANNEL = "latest"

REPO_ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE_PATH = REPO_ROOT / "app/build.gradle.kts"
APK_PATH = REPO_ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AAPT_PATH = Path.home() / "Library/Android/sdk/build-tools/36.0.0/aapt"
OUTPUT_ROOT = REPO_ROOT / "dist/android-host"

PACKAGE_NAME = "com.nsn8.vued"


def main() -> None:
    args = parse_args()
    if args.bump:
        version_name, version_code = bump_gradle_version(BUILD_GRADLE_PATH)
        build_apk()
    else:
        version_name = args.version_name
        version_code = args.version_code

    apk_path = APK_PATH
    if not apk_path.is_file():
        raise SystemExit(f"APK not found: {apk_path}")

    validate_apk_metadata(apk_path, version_name, version_code)
    validate_published_version(args.supabase_url, version_name, version_code)

    apk_name = f"vued-host-{version_code}.apk"
    apk_output_path = OUTPUT_ROOT / str(version_code) / apk_name
    manifest_output_path = OUTPUT_ROOT / CHANNEL / "manifest.json"
    apk_remote_path = f"{PREFIX}/{version_code}/{apk_name}"

    apk_output_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_output_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk_path, apk_output_path)

    manifest = {
        "channel": CHANNEL,
        "packageName": PACKAGE_NAME,
        "versionName": version_name,
        "versionCode": version_code,
        "url": public_object_url(args.supabase_url, apk_remote_path),
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare Android host update files for manual Supabase Storage upload.",
    )
    parser.add_argument(
        "--supabase-url",
        default=DEFAULT_SUPABASE_URL,
        help=f"Supabase project URL. Defaults to {DEFAULT_SUPABASE_URL}.",
    )
    parser.add_argument(
        "--bump",
        action="store_true",
        help="Bump app/build.gradle.kts, build the debug APK, and publish that bumped version.",
    )
    parser.add_argument(
        "--version-name",
        help="APK versionName to publish, for example 3.0.",
    )
    parser.add_argument(
        "--version-code",
        type=int,
        help="APK versionCode to publish, for example 3.",
    )
    args = parser.parse_args()
    if args.bump and (args.version_name is not None or args.version_code is not None):
        parser.error("--bump cannot be combined with --version-name or --version-code")
    if not args.bump and (args.version_name is None or args.version_code is None):
        parser.error("--version-name and --version-code are required unless --bump is used")
    return args


def bump_gradle_version(path: Path) -> tuple[str, int]:
    text = path.read_text()
    default_config_match = re.search(
        r"(?ms)^(?P<indent>\s*)defaultConfig\s*\{(?P<body>.*?)(?P=indent)\}",
        text,
    )
    if not default_config_match:
        raise SystemExit(f"Could not find defaultConfig block in {path}")

    body = default_config_match.group("body")
    version_code_match = re.search(r"(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$", body)
    version_name_match = re.search(r'(?m)^(\s*versionName\s*=\s*")([^"]+)("\s*)$', body)
    if not version_code_match:
        raise SystemExit(f"Could not find versionCode in {path}")
    if not version_name_match:
        raise SystemExit(f"Could not find versionName in {path}")

    current_version_code = int(version_code_match.group(2))
    current_version_name = version_name_match.group(2)
    next_version_code = current_version_code + 1
    next_version_name = bump_version_name(current_version_name, current_version_code, next_version_code)

    body = (
        body[: version_code_match.start()]
        + f"{version_code_match.group(1)}{next_version_code}{version_code_match.group(3)}"
        + body[version_code_match.end() :]
    )
    version_name_match = re.search(r'(?m)^(\s*versionName\s*=\s*")([^"]+)("\s*)$', body)
    body = (
        body[: version_name_match.start()]
        + f"{version_name_match.group(1)}{next_version_name}{version_name_match.group(3)}"
        + body[version_name_match.end() :]
    )
    text = text[: default_config_match.start("body")] + body + text[default_config_match.end("body") :]
    path.write_text(text)

    print(
        "Bumped Gradle version: "
        f"{current_version_name} ({current_version_code}) -> {next_version_name} ({next_version_code})"
    )
    return next_version_name, next_version_code


def bump_version_name(version_name: str, current_version_code: int, next_version_code: int) -> str:
    if not re.fullmatch(r"\d+(?:\.\d+)*", version_name):
        raise SystemExit(f"Cannot bump non-numeric versionName automatically: {version_name}")

    parts = [int(part) for part in version_name.split(".")]
    if parts[0] == current_version_code:
        parts[0] = next_version_code
    else:
        parts[-1] += 1
    return ".".join(str(part) for part in parts)


def build_apk() -> None:
    print("Building debug APK...")
    try:
        subprocess.run([str(REPO_ROOT / "gradlew"), ":app:assembleDebug"], cwd=REPO_ROOT, check=True)
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"APK build failed with exit code {error.returncode}") from error


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_apk_metadata(apk_path: Path, version_name: str, version_code: int) -> None:
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
    if actual_version_code != version_code:
        mismatches.append(f"--version-code is {version_code}, APK has {actual_version_code}")
    if actual_version_name != version_name:
        mismatches.append(f"--version-name is {version_name}, APK has {actual_version_name}")
    if mismatches:
        raise SystemExit("APK metadata does not match publish arguments:\n- " + "\n- ".join(mismatches))
    print(f"Validated APK: {actual_package} {actual_version_name} ({actual_version_code})")


def validate_published_version(supabase_url: str, version_name: str, version_code: int) -> None:
    manifest_url = public_object_url(supabase_url, f"{PREFIX}/{CHANNEL}/manifest.json")
    current = fetch_json(manifest_url)
    if current is None:
        print(f"No current manifest found at {manifest_url}")
        return

    current_package = current.get("packageName")
    current_version_code = int(current.get("versionCode", 0))
    current_version_name = str(current.get("versionName", ""))

    if current_package and current_package != PACKAGE_NAME:
        raise SystemExit(
            "Published manifest packageName does not match publish arguments:\n"
            f"- current manifest has {current_package}\n"
            f"- script has {PACKAGE_NAME}"
        )
    if version_code <= current_version_code:
        raise SystemExit(
            "--version-code must be higher than the currently published manifest:\n"
            f"- current manifest: {current_version_name} ({current_version_code})\n"
            f"- publish arguments: {version_name} ({version_code})"
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


def public_object_url(supabase_url: str, remote_path: str) -> str:
    return (
        f"{supabase_url.rstrip('/')}/storage/v1/object/public/"
        f"{quote(BUCKET)}/{quote_path(remote_path)}"
    )


def quote_path(path: str) -> str:
    return "/".join(quote(part) for part in path.split("/"))


def open_output_folder(path: Path) -> None:
    subprocess.run(["open", str(path)], check=False)
    print(f"Opened folder: {path}")


if __name__ == "__main__":
    main()
