#!/usr/bin/env python3
from pathlib import Path

workflow = Path(".github/workflows/multiplatform.yml").read_text(encoding="utf-8")
lower = workflow.lower()

forbidden = {
    "rolling release": "rolling release job/name",
    'tag_name="latest-v': "rolling latest-v tag",
    "gh release edit ": "editing an existing release",
    "gh release delete ": "deleting an existing release",
    "gh release delete-asset ": "deleting an existing release asset",
    "--clobber": "clobbering an existing release asset",
    "git tag -f": "force-moving a tag",
    "git tag -fa": "force-moving an annotated tag",
    'git push origin "$tag_name" --force': "force-pushing a version tag",
}

violations = [label for token, label in forbidden.items() if token in lower]

required = 'TAG_NAME="v' + '${VERSION_NAME}"'
if required not in workflow:
    violations.append("missing immutable semantic-version tag TAG_NAME=v${VERSION_NAME}")

if violations:
    raise SystemExit(
        "Release immutability policy violated:\n- " + "\n- ".join(sorted(set(violations)))
    )

print("Release immutability policy verified.")
