#!/usr/bin/env python3
"""Record the locked Linux Cargo graph and upstream license evidence."""
import argparse
import hashlib
import json
from pathlib import Path

# These manifests omit SPDX metadata; identifiers below were checked against the
# nearest upstream license text, whose digest is retained in the inventory.
LICENSE_EVIDENCE = {
    "apply": "Unlicense",
    "cosmic-config": "MPL-2.0", "cosmic-config-derive": "MPL-2.0",
    "cosmic-settings-daemon": "MPL-2.0", "cosmic-theme": "MPL-2.0",
    "libcosmic": "MPL-2.0", "dnd": "MIT", "mime": "MIT", "iced_accessibility": "MIT",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text())
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    selected = set()
    pending = list(metadata["workspace_members"])
    while pending:
        identity = pending.pop()
        if identity in selected:
            continue
        selected.add(identity)
        pending.extend(dep["pkg"] for dep in nodes[identity]["deps"])
    packages = []
    for package in metadata["packages"]:
        if package["id"] not in selected or not package["source"]:
            continue
        declared = package.get("license")
        item = {"name": package["name"], "version": package["version"],
                "source": package["source"], "declared_license": declared}
        if not declared:
            inferred = LICENSE_EVIDENCE.get(package["name"])
            if not inferred:
                raise SystemExit(f"License evidence missing for {package['name']}")
            root = Path(package["manifest_path"]).parent
            found = []
            for ancestor in [root, *list(root.parents)[:4]]:
                found = sorted(path for path in ancestor.iterdir() if path.is_file()
                               and path.name.lower().startswith(("license", "licence", "copying")))
                if found:
                    break
            if not found:
                raise SystemExit(f"Upstream license text missing for {package['name']}")
            item["reviewed_license"] = inferred
            item["license_texts"] = [{"filename": path.name,
                                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                                     for path in found]
        packages.append(item)
    result = {"target": "x86_64-unknown-linux-gnu", "scope": "Cargo build graph including build/dev dependencies; not a release notice bundle",
              "packages": sorted(packages, key=lambda item: (item["name"], item["version"]))}
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Recorded {len(packages)} dependency license entries.")


if __name__ == "__main__":
    main()
