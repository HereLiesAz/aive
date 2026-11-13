#!/usr/bin/env python3
"""Release immutability and version policy check.

Scope: the forbidden-command scan covers EVERY workflow under .github/workflows (a release could be
edited or deleted from any of them), while the version/asset fragments are specific to
multiplatform.yml, the only workflow that builds and publishes the four-part-versioned release
assets (APK, DEB, MSI, DMG). The Play bundle is versioned by versionCode, not by file name, so it
has no name fragment to check. Also checks that the vendor checkout pins agree across workflows, and
that every artifact a workflow downloads is uploaded by a step in that same workflow.
"""
from pathlib import Path
import re


def artifact_contract_violations(name, text):
    """Names a workflow downloads that none of its steps uploads.

    A templated upload name (`${{ matrix.artifact }}`) is resolved from the literal `artifact:`
    values of the workflow's matrix entries.
    """
    uploads = set()
    downloads = set()
    for block in re.finditer(
        r"uses: actions/(upload|download)-artifact@[^\n]*\n((?:[ \t]+[^\n]*\n?)*)", text
    ):
        found = re.search(r"^\s+name: (\S+)", block.group(2), re.MULTILINE)
        if found:
            (uploads if block.group(1) == "upload" else downloads).add(found.group(1))
    if any("${{" in u for u in uploads):
        uploads.update(m.group(1) for m in re.finditer(r"^\s+artifact: (\S+)", text, re.MULTILINE))
    return [
        f"{name} downloads artifact '{d}' that no step in the workflow uploads"
        for d in sorted(downloads)
        if "${{" not in d and d not in uploads
    ]


def main():
    workflow = Path(".github/workflows/multiplatform.yml").read_text(encoding="utf-8")
    all_workflows = {
        path.name: path.read_text(encoding="utf-8")
        for path in sorted(Path(".github/workflows").glob("*.yml"))
    }
    properties = Path("gradle.properties").read_text(encoding="utf-8")

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

    violations = [
        f"{label} ({name})"
        for name, text in all_workflows.items()
        for token, label in forbidden.items()
        if token in text.lower()
    ]

    # The two vendor checkouts must be pinned to one full SHA each, identically everywhere.
    for repository in ("HereLiesAz/conveyance-h2g2", "HereLiesAz/Conveyance"):
        pins = {}
        for name, text in all_workflows.items():
            for found in re.finditer(
                r"repository: " + re.escape(repository) + r"\n\s+(?:path: [^\n]+\n\s+)?ref: ([^\s#]+)", text
            ):
                pins.setdefault(found.group(1), []).append(name)
            if re.search(r"repository: " + re.escape(repository) + r"\n(?!\s+(?:path: [^\n]+\n\s+)?ref: )", text):
                violations.append(f"{repository} checkout in {name} has no ref: pin")
        for pin in pins:
            if not re.fullmatch(r"[0-9a-f]{40}", pin):
                violations.append(f"{repository} pin {pin} is not a full commit SHA")
        if len(pins) > 1:
            violations.append(f"{repository} is pinned to different SHAs: {sorted(pins)}")

    match = re.search(r"^app\.versionName=(\d+)\.(\d+)\.(\d+)\.(\d+)$", properties, re.MULTILINE)
    if not match:
        violations.append("app.versionName is not MAJOR.MINOR.PATCH.BUILD")
    elif match.group(4) != "0":
        violations.append("checked-in app.versionName BUILD must be 0; CI supplies the release build number")

    required_fragments = {
        'AIVE_VERSION: ${{ needs.version.outputs.version }}':
            "desktop-packages job does not use the central four-part version from the version job",
        'build-number: ${{ github.run_number }}':
            "build job does not pass github.run_number to four-part-version action",
        'build-version: ${{ steps.version.outputs.version }}':
            "release job does not pass the four-part version to the release action",
        'release-files/TheAive-${VERSION_NAME}.deb':
            "DEB release asset does not contain the full four-part version",
        'release-files/TheAive-${VERSION_NAME}.msi':
            "MSI release asset does not contain the full four-part version",
        'release-files/TheAive-${VERSION_NAME}.dmg':
            "DMG release asset does not contain the full four-part version",
    }

    for name, text in all_workflows.items():
        violations.extend(artifact_contract_violations(name, text))

    for fragment, label in required_fragments.items():
        if fragment not in workflow:
            violations.append(label)

    if violations:
        raise SystemExit(
            "Release immutability/version policy violated:\n- "
            + "\n- ".join(sorted(set(violations)))
        )

    print("Immutable MAJOR.MINOR.PATCH.BUILD release policy verified.")


if __name__ == "__main__":
    main()
