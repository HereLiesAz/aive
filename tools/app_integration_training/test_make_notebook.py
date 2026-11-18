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
            "SCORE.score(",
            "SCORE.gates(",
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

    def test_scoring_uses_score_py_as_it_really_is(self):
        """The notebook once called score_rows(), which score.py does not have. Run the notebook's own wrappers against
        the real score.py on rows in the Kotlin writer's shape."""
        import collections
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def call_score" in s)
        wanted = {"call_score", "call_gates"}
        body = [n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name in wanted]
        spec = importlib.util.spec_from_file_location("aive_app_integration_score", HERE / "score.py")
        score = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(score)
        namespace = {"SCORE": score, "collections": collections, "CORPUS_ISSUES": []}
        exec(compile(ast.Module(body, []), "scoring", "exec"), namespace)

        def row(index, split):
            return {"id": f"F1-{split}-{index}", "family": "F1", "sub": "F1a", "split": split, "checks": {"exact": "ok"},
                    "checks_version": score.CHECKS_VERSION,
                    "messages": [{"role": "system", "content": "s"}, {"role": "user", "content": "u"},
                                 {"role": "assistant", "content": "ok"}]}

        rows = [row(i, "test") for i in range(4)] + [row(i, "adversarial") for i in range(2)]
        self.assertEqual({"F1": 1.0}, namespace["call_score"](rows, ["ok"] * 6))
        self.assertEqual({"F1": 0.0}, namespace["call_score"](rows, ["nope"] * 6))
        by_split = {"test": rows[:4], "adversarial": rows[4:]}
        ok, failed = namespace["call_gates"](by_split, {"test": ["ok"] * 4, "adversarial": ["ok"] * 2})
        self.assertFalse(ok)   # the other families have no rows here, so their gates cannot pass
        self.assertNotIn("F1 on test >= 0.90", failed)

    def test_every_long_stage_saves_its_progress(self):
        text = "\n".join(code_cells(json.loads(NOTEBOOK.read_text())))
        # training: a checkpoint every SAVE_STEPS steps, not once per epoch
        self.assertIn('save_strategy="steps", save_steps=SAVE_STEPS', text)
        self.assertNotIn('save_strategy="epoch"', text)
        self.assertIn("SAVE_STEPS = 50", text)
        # both generation gates keep each answer on disk as it is produced
        self.assertIn("store=store", text)
        self.assertEqual(2, text.count("AnswerStore(WORK /"))

    def test_resume_survives_a_missing_scaler_and_a_missing_gpu_stops_early(self):
        text = "\n".join(code_cells(json.loads(NOTEBOOK.read_text())))
        self.assertIn('scaler.pt', text)
        self.assertIn('trainer.accelerator, "scaler", None) is None', text)
        self.assertIn("No GPU. Runtime > Change runtime type > GPU", text)

    def test_a_failed_gate_repairs_only_the_failing_families(self):
        text = "\n".join(code_cells(json.loads(NOTEBOOK.read_text())))
        self.assertIn("def repair(families, round_no)", text)
        self.assertIn("failing_families(RESULTS[\"gate1\"][\"gateFailures\"])", text)
        self.assertIn("REPAIR_ROUNDS = 2", text)
        # the collator must not see the family: it lives beside the rows, not in them
        self.assertNotIn('"labels": [-100] * len(prompt) + answer, "family"', text)

    def test_the_answer_store_resumes_and_never_trusts_a_half_written_file(self):
        import hashlib
        import tempfile
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "class AnswerStore" in s)
        node = next(n for n in ast.parse(source).body if isinstance(n, ast.ClassDef) and n.name == "AnswerStore")
        namespace = {"Path": Path, "json": json, "hashlib": hashlib}
        exec(compile(ast.Module([node], []), "answer_store", "exec"), namespace)
        AnswerStore = namespace["AnswerStore"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "answers.json"
            first = AnswerStore(path)
            self.assertIsNone(first.get("prompt a"))
            first.put("prompt a", "answer a")
            first.put("prompt b", "answer b")
            first.save()
            second = AnswerStore(path)             # a new session after a disconnect
            self.assertEqual("answer a", second.get("prompt a"))
            self.assertEqual("answer b", second.get("prompt b"))
            self.assertIsNone(second.get("prompt c"))
            self.assertFalse(Path(str(path) + ".tmp").exists())
            path.write_text('{"half": "writ')      # a save cut short
            self.assertEqual({}, AnswerStore(path).answers)

    def test_cells_after_the_state_cell_say_what_to_do_when_run_alone(self):
        cells = code_cells(json.loads(NOTEBOOK.read_text()))
        index = next(i for i, c in enumerate(cells) if "state = json.loads(STATE_FILE" in c)
        for i, cell in enumerate(cells):
            if i <= index:
                self.assertFalse(cell.startswith('if "state" not in globals()'), f"cell {i} is guarded but runs before state exists")
            else:
                self.assertTrue(cell.startswith('if "state" not in globals()'), f"cell {i} lacks the guard")
        guard = cells[index + 1].split("\n")[:2]
        with self.assertRaisesRegex(RuntimeError, "Run all"):
            exec("\n".join(guard) + "\n", {"__builtins__": __builtins__})

    def test_a_rerun_with_nothing_changed_skips_the_toolchain(self):
        import tempfile
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def build_corpus" in s)
        node = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name == "build_corpus")
        sha = "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            out = work / "corpus-build" / "aive-app-integration-corpus"
            out.mkdir(parents=True)
            for name in ("manifest.json", "score.py"):
                (out / name).write_text("x")

            def forbidden(*args, **kwargs):
                raise AssertionError("the toolchain must not be touched when the corpus is already built")

            namespace = {"WORK": work, "state": {"corpusBuild": {"commit": sha, "rows": None}}, "RESULTS": {},
                         "remote_commit": lambda: sha, "CORPUS_ROWS": None, "SOURCE_REPOSITORY": "r", "SOURCE_REF": "main",
                         "ensure_jdk": forbidden, "resolve_source": forbidden, "ensure_vendor": forbidden, "run_gradle": forbidden}
            exec(compile(ast.Module([node], []), "build_corpus", "exec"), namespace)
            self.assertEqual((out, sha), namespace["build_corpus"]())
            self.assertTrue(namespace["RESULTS"]["corpusBuild"]["reused"])
            # a newer commit must NOT reuse the old build
            namespace["remote_commit"] = lambda: "b" * 40
            with self.assertRaisesRegex(AssertionError, "toolchain"):
                namespace["build_corpus"]()

    def test_manifest_entries_reads_the_shape_the_kotlin_writer_produces(self):
        """The first real Colab run failed with 'manifest.json lists train, which the generator did not write':
        the writer keys files by split and names the file inside the entry."""
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def manifest_entries" in s)
        function = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name == "manifest_entries")
        namespace = {}
        exec(compile(ast.Module([function], []), "manifest_entries", "exec"), namespace)
        entries = namespace["manifest_entries"]
        writer = {"files": {"train": {"file": "train.jsonl", "sha256": "a" * 64, "rows": 3},
                            "adversarial": {"file": "adversarial.jsonl", "sha256": "b" * 64, "rows": 9}}}
        self.assertEqual([("train.jsonl", "a" * 64), ("adversarial.jsonl", "b" * 64)], entries(writer))
        self.assertEqual([("x.jsonl", "c" * 64)], entries({"files": {"x.jsonl": "c" * 64}}))
        self.assertEqual([("y.jsonl", "d" * 64)], entries({"files": [{"path": "y.jsonl", "sha256": "d" * 64}]}))
        self.assertEqual([("z.jsonl", "e" * 64)], entries({"files": {"z": {"path": "z.jsonl", "sha256": "e" * 64}}}))

    def test_the_base_is_imported_from_the_first_source_that_has_it(self):
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def published_base" in s)
        function = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name == "published_base")

        def run(kaggle, hugging_face, github):
            calls = []

            def make(name, outcome):
                def source(*args):
                    calls.append(name)
                    if isinstance(outcome, Exception):
                        raise outcome
                    return outcome
                return source

            namespace = {"Path": Path, "base_in": lambda directory: None,
                         "base_from_kaggle": make("kaggle", kaggle),
                         "base_from_huggingface": make("huggingface", hugging_face),
                         "base_from_github": make("github", github)}
            exec(compile(ast.Module([function], []), "published_base", "exec"), namespace)
            return namespace["published_base"](Path("/nonexistent")), calls

        found = ("data", b"raw", Path("a.tar.gz"))
        self.assertEqual((found, ["kaggle"]), run(found, None, None))
        self.assertEqual((found, ["kaggle", "huggingface", "github"]), run(None, None, found))
        self.assertEqual((found, ["kaggle", "huggingface"]), run(AssertionError("bad sha"), found, None))
        self.assertEqual((None, ["kaggle", "huggingface", "github"]), run(None, None, None))

    def test_no_key_is_ever_printed_when_importing_the_base(self):
        notebook = json.loads(NOTEBOOK.read_text())
        source = next(s for s in code_cells(notebook) if "def base_from_kaggle" in s)
        for name in ("base_from_kaggle", "base_from_huggingface"):
            function = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name == name)
            for call in (n for n in ast.walk(function) if isinstance(n, ast.Call) and getattr(n.func, "id", "") == "print"):
                names = {n.id for n in ast.walk(call) if isinstance(n, ast.Name)}
                self.assertFalse(names & {"key", "token"}, f"{name} prints a key: {ast.unparse(call)}")

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
