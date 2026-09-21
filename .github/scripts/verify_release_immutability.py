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
        "desktop-packages job does not derive MAJOR.MINOR.PATCH.BUILD from github.run_number",
    'build-number: ${{ github.run_number }}':
        "build job does not pass github.run_number to four-part-version action",
    'build-version: ${{ steps.version.outputs.version }}':
        "release job does not pass the four-part version to the release action",
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
