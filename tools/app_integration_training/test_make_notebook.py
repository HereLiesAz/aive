"""The committed notebook must be exactly what make_notebook.py generates, and must be sound.

Run: python3 -m unittest discover -s tools/app_integration_training -p 'test_*.py'
Edit make_notebook.py, regenerate with `python3 tools/app_integration_training/make_notebook.py`, commit both.
"""
import ast
import importlib.util
import json
import re
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
NOTEBOOK = HERE / "app_integration_adapter.ipynb"


def load_generator():
    spec = importlib.util.spec_from_file_location("app_integration_make_notebook", HERE / "make_notebook.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def code_cells(notebook):
    return [("".join(c["source"])) for c in notebook["cells"] if c["cell_type"] == "code"]


class MakeNotebookTest(unittest.TestCase):
    def test_committed_notebook_is_the_generator_output(self):
        generated = load_generator().render()
        self.assertEqual(
            generated,
            NOTEBOOK.read_text(),
            "app_integration_adapter.ipynb is stale: run python3 tools/app_integration_training/make_notebook.py",
        )

    def test_notebook_is_valid_nbformat(self):
        try:
            import nbformat
        except ImportError:
            self.skipTest("nbformat is not installed")
        notebook = nbformat.read(str(NOTEBOOK), as_version=4)
        nbformat.validate(notebook)

    def test_every_code_cell_parses_once_magics_are_removed(self):
        notebook = json.loads(NOTEBOOK.read_text())
        for index, source in enumerate(code_cells(notebook)):
            stripped = "\n".join("" if line.lstrip().startswith(("%", "!")) else line for line in source.splitlines())
            try:
                ast.parse(stripped)
            except SyntaxError as failure:
                self.fail(f"code cell {index} does not parse: {failure}")

    def test_no_cell_holds_a_secret(self):
        notebook = json.loads(NOTEBOOK.read_text())
        patterns = {
            "GitHub token": r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}",
            "Kaggle or generic hex key": r"(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])",
            "bearer literal": r"Bearer\s+[A-Za-z0-9_\-]{20,}",
            "assigned secret": r"(?i)(token|secret|password|api_key)\s*=\s*[\"'][^\"']{8,}[\"']",
        }
        for index, cell in enumerate(notebook["cells"]):
            text = "".join(cell["source"])
            for name, pattern in patterns.items():
                self.assertIsNone(re.search(pattern, text), f"cell {index} looks like it holds a {name}")

    def test_the_token_is_never_printed(self):
        text = "\n".join(code_cells(json.loads(NOTEBOOK.read_text())))
        for line in text.splitlines():
            if "print(" in line:
                self.assertNotRegex(line, r"\btoken\b", f"a print call mentions the token: {line.strip()}")

    def test_switches_and_contract_are_present(self):
        text = "\n".join(code_cells(json.loads(NOTEBOOK.read_text())))
        for required in (
            "SMOKE = False",
            "EXPORT_LITERTLM = True",
            "LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05",
            "MAX_NEW = 512",
            "KV_CACHE_TOKENS = 1280",
            '"<|im_start|>system\\n"',
            "score_rows",
            "gates",
            "app-integration-v1",
            "BUILD_CORPUS_ON_KAGGLE = True",
            'SOURCE_REF = "main"',
            "GENERATOR_GRADLE_ARGS",
            "AIVE_APP_INTEGRATION_ROWS",
            "CORPUS_ROWS = 20",
            "GRADLE_USER_HOME",
        ):
            self.assertIn(required, text, f"notebook lacks {required!r}")

    def test_the_generator_invocation_lives_in_one_constant(self):
        text = "\n".join(code_cells(json.loads(NOTEBOOK.read_text())))
        self.assertEqual(1, text.count(":shared:desktopTest"))
        self.assertEqual(1, text.count("-Daive.appIntegration.outDir"))

    def test_the_corpus_zip_is_deterministic(self):
        import os
        import tempfile
        import zipfile

        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def deterministic_zip" in s)
        function = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name == "deterministic_zip")
        namespace = {"Path": Path, "zipfile": zipfile}
        exec(compile(ast.Module([function], []), "deterministic_zip", "exec"), namespace)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, name in enumerate(("b.jsonl", "a.jsonl", "sub/manifest.json")):
                path = root / "data" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"row {index}\n")
            first = namespace["deterministic_zip"](root / "data", root / "one.zip", "corpus").read_bytes()
            for path in (root / "data").rglob("*"):
                os.utime(path, (1_000_000_000, 1_000_000_000))
            second = namespace["deterministic_zip"](root / "data", root / "two.zip", "corpus").read_bytes()
            self.assertEqual(first, second)
            with zipfile.ZipFile(root / "one.zip") as archive:
                self.assertEqual(sorted(archive.namelist()), archive.namelist())

    def test_the_vendored_builds_are_checked_out_at_ci_pins(self):
        """The first real Kaggle build failed ('No matching variant ... conveyance-h2g2-android') because a plain clone
        lacks vendor/conveyance-h2g2 and vendor/.../Conveyance, which CI checks out at pinned commits."""
        import shutil
        import subprocess
        import tempfile

        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def ensure_vendor" in s)
        wanted = {"VENDOR_HOST", "VENDOR_PATHS", "vendor_pins", "ensure_vendor"}
        body = []
        for node in ast.parse(source).body:
            names = {t.id for t in getattr(node, "targets", []) if isinstance(t, ast.Name)} | {getattr(node, "name", "")}
            if names & wanted:
                body.append(node)

        def sh(command, cwd=None):
            return subprocess.run(command, cwd=cwd, check=True, capture_output=True, text=True).stdout

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pins = {}
            for repository in ("HereLiesAz/conveyance-h2g2", "HereLiesAz/Conveyance"):
                origin = root / "origin" / repository
                origin.mkdir(parents=True)
                sh(["git", "init", "-q"], cwd=origin)
                (origin / "README.md").write_text(repository)
                sh(["git", "add", "."], cwd=origin)
                sh(["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-qm", "pin"], cwd=origin)
                pins[repository] = sh(["git", "rev-parse", "HEAD"], cwd=origin).strip()
                sh(["git", "config", "uploadpack.allowAnySHA1InWant", "true"], cwd=origin)
            checkout = root / "checkout"
            (checkout / ".github" / "workflows").mkdir(parents=True)
            workflow = "\n".join(
                f"        with:\n          repository: {repo}\n          ref: {sha} # pinned\n          path: x"
                for repo, sha in pins.items())
            (checkout / ".github" / "workflows" / "multiplatform.yml").write_text(workflow)
            namespace = {"Path": Path, "re": re, "shutil": shutil, "subprocess": subprocess, "sh": sh}
            exec(compile(ast.Module(body, []), "ensure_vendor", "exec"), namespace)
            namespace["VENDOR_HOST"] = str(root / "origin")
            self.assertEqual(pins, namespace["vendor_pins"](checkout))
            namespace["ensure_vendor"](checkout)
            self.assertEqual("HereLiesAz/conveyance-h2g2", (checkout / "vendor/conveyance-h2g2/README.md").read_text())
            self.assertEqual("HereLiesAz/Conveyance",
                             (checkout / "vendor/conveyance-h2g2/vendor/Conveyance/README.md").read_text())
            # a rerun finds both already at the pin and fetches nothing
            namespace["VENDOR_HOST"] = str(root / "does-not-exist")
            namespace["ensure_vendor"](checkout)

    def test_a_missing_pin_stops_with_a_plain_message(self):
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def vendor_pins" in s)
        import tempfile
        wanted = {"VENDOR_PATHS", "vendor_pins"}
        body = [n for n in ast.parse(source).body
                if ({t.id for t in getattr(n, "targets", []) if isinstance(t, ast.Name)} | {getattr(n, "name", "")}) & wanted]
        namespace = {"Path": Path, "re": re}
        exec(compile(ast.Module(body, []), "vendor_pins", "exec"), namespace)
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            (checkout / ".github" / "workflows").mkdir(parents=True)
            (checkout / ".github" / "workflows" / "multiplatform.yml").write_text("name: x\n")
            with self.assertRaises(RuntimeError):
                namespace["vendor_pins"](checkout)


if __name__ == "__main__":
    unittest.main()
