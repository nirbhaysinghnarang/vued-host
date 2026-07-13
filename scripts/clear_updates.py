#!/usr/bin/env python3
"""
Clear Android host update artifacts from Supabase Storage.

Edit the constants below, then run:

    python3 scripts/clear_updates.py

This deletes objects under downloads/android-host/. It does not delete the
bucket itself.
"""

from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import quote


SUPABASE_URL = "https://eubvwnuocitdcctwqjox.supabase.co"
SUPABASE_SERVICE_ROLE_KEY = "replace-with-supabase-service-role-key"
BUCKET = "downloads"
PREFIX = "android-host"
DELETE_BATCH_SIZE = 100


def main() -> None:
    if SUPABASE_SERVICE_ROLE_KEY == "replace-with-supabase-service-role-key":
        raise SystemExit("Edit SUPABASE_SERVICE_ROLE_KEY in scripts/clear_updates.py first.")

    objects = list_objects_recursive(PREFIX)
    if not objects:
        print(f"No objects found under {BUCKET}/{PREFIX}")
        return

    print(f"Deleting {len(objects)} objects from {BUCKET}/{PREFIX}:")
    for path in objects:
        print(f"- {path}")

    for index in range(0, len(objects), DELETE_BATCH_SIZE):
        delete_objects(objects[index:index + DELETE_BATCH_SIZE])

    print(f"Cleared {len(objects)} update objects.")


def list_objects_recursive(prefix: str) -> list[str]:
    found: list[str] = []
    for item in list_objects(prefix):
        name = item.get("name")
        if not name:
            continue
        path = f"{prefix.rstrip('/')}/{name}"
        if item.get("id") or item.get("metadata"):
            found.append(path)
        else:
            found.extend(list_objects_recursive(path))
    return found


def list_objects(prefix: str) -> list[dict]:
    body = {
        "prefix": prefix.strip("/"),
        "limit": 1000,
        "offset": 0,
        "sortBy": {"column": "name", "order": "asc"},
    }
    output = curl_json(
        [
            "--request",
            "POST",
            "--header",
            "Content-Type: application/json",
            "--data",
            json.dumps(body),
            f"{SUPABASE_URL.rstrip('/')}/storage/v1/object/list/{quote(BUCKET)}",
        ],
    )
    return json.loads(output or "[]")


def delete_objects(paths: list[str]) -> None:
    if not paths:
        return
    body = {"prefixes": paths}
    curl_json(
        [
            "--request",
            "DELETE",
            "--header",
            "Content-Type: application/json",
            "--data",
            json.dumps(body),
            f"{SUPABASE_URL.rstrip('/')}/storage/v1/object/{quote(BUCKET)}",
        ],
    )
    print(f"Deleted {len(paths)} objects")


def curl_json(args: list[str]) -> str:
    with tempfile.NamedTemporaryFile(prefix="vued-clear-storage-") as output_file:
        command = [
            "curl",
            "--fail",
            "--show-error",
            "--silent",
            "--http1.1",
            "--retry",
            "3",
            "--retry-all-errors",
            "--retry-delay",
            "2",
            "--output",
            output_file.name,
            "--header",
            f"Authorization: Bearer {SUPABASE_SERVICE_ROLE_KEY}",
            "--header",
            f"apikey: {SUPABASE_SERVICE_ROLE_KEY}",
            *args,
        ]
        subprocess.run(command, check=True)
        return Path(output_file.name).read_text()


if __name__ == "__main__":
    main()
