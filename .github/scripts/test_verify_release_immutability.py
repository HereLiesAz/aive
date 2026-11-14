import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from verify_release_immutability import artifact_contract_violations  # noqa: E402


class ArtifactContractTest(unittest.TestCase):
    def test_download_without_upload_is_reported(self):
        text = (
            "      - uses: actions/download-artifact@abc # v8\n"
            "        with:\n"
            "          name: gone\n"
            "          path: x\n"
        )
        self.assertEqual(1, len(artifact_contract_violations("w.yml", text)))

    def test_matching_upload_and_matrix_names_pass(self):
        text = (
            "          - artifact: aive-desktop-linux\n"
            "      - uses: actions/upload-artifact@abc # v7\n"
            "        with:\n"
            "          name: ${{ matrix.artifact }}\n"
            "          path: p\n"
            "      - uses: actions/upload-artifact@abc # v7\n"
            "        with:\n"
            "          name: aive-release-notes\n"
            "      - uses: actions/download-artifact@abc # v8\n"
            "        with:\n"
            "          name: aive-desktop-linux\n"
            "      - uses: actions/download-artifact@abc # v8\n"
            "        with:\n"
            "          name: aive-release-notes\n"
        )
        self.assertEqual([], artifact_contract_violations("w.yml", text))

    def test_adjacent_steps_without_blank_line_are_checked_individually(self):
        text = (
            "      - uses: actions/upload-artifact@abc\n"
            "        with:\n"
            "          name: present\n"
            "      - uses: actions/download-artifact@abc\n"
            "        with:\n"
            "          name: missing\n"
        )
        self.assertEqual(1, len(artifact_contract_violations("w.yml", text)))

    def test_real_workflows_satisfy_contract(self):
        root = Path(__file__).resolve().parents[2] / ".github" / "workflows"
        for path in root.glob("*.yml"):
            self.assertEqual(
                [], artifact_contract_violations(path.name, path.read_text(encoding="utf-8"))
            )

    def test_android_apk_upload_and_download_names_match(self):
        text = (Path(__file__).resolve().parents[2] / ".github" / "workflows" / "multiplatform.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("name: aive-android-github-release", text)
        self.assertIn("TheAive-${VERSION_NAME}-android.apk", text)
        self.assertIn("path: androidApp/build/outputs/apk/github/release/*.apk", text)
        self.assertEqual([], artifact_contract_violations("multiplatform.yml", text))


if __name__ == "__main__":
    unittest.main()
