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

    def test_real_workflows_satisfy_contract(self):
        root = Path(__file__).resolve().parents[2] / ".github" / "workflows"
        for path in root.glob("*.yml"):
            self.assertEqual(
                [], artifact_contract_violations(path.name, path.read_text(encoding="utf-8"))
            )


if __name__ == "__main__":
    unittest.main()
