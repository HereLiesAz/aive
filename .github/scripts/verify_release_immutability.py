#!/usr/bin/env python3
from pathlib import Path
import re

workflow = Path(".github/workflows/multiplatform.yml").read_text(encoding="utf-8")
properties = Path("gradle.properties").read_text(encoding="utf-8")
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

match = re.search(r"^app\.versionName=(\d+)\.(\d+)\.(\d+)\.(\d+)$", properties, re.MULTILINE)
if not match:
    violations.append("app.versionName is not MAJOR.MINOR.PATCH.BUILD")
elif match.group(4) != "0":
    violations.append("checked-in app.versionName BUILD must be 0; CI supplies the release build number")

required_fragments = {
    'AIVE_VERSION="${BASE_VERSION}.${GITHUB_RUN_NUMBER}"':
        "build jobs do not derive MAJOR.MINOR.PATCH.BUILD from github.run_number",
    'VERSION_NAME="${MAJOR}.${MINOR}.${PATCH}.${GITHUB_RUN_NUMBER}"':
        "release job does not derive a four-part version",
    'TAG_NAME="v${VERSION_NAME}"':
        "release tag is not tied to the full four-part version",
    'release-files/TheAive-${VERSION_NAME}-android.apk':
        "Android release asset does not contain the full four-part version",
    'release-files/TheAive-${VERSION_NAME}.deb':
        "DEB release asset does not contain the full four-part version",
    'release-files/TheAive-${VERSION_NAME}.msi':
        "MSI release asset does not contain the full four-part version",
    'release-files/TheAive-${VERSION_NAME}.dmg':
        "DMG release asset does not contain the full four-part version",
}

for fragment, label in required_fragments.items():
    if fragment not in workflow:
        violations.append(label)

if violations:
    raise SystemExit(
        "Release immutability/version policy violated:\n- "
        + "\n- ".join(sorted(set(violations)))
    )

print("Immutable MAJOR.MINOR.PATCH.BUILD release policy verified.")
