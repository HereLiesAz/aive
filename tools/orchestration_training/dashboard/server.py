"""Lightweight real-time Mission Control server for AIVE Orchestration Specialists."""
import http.server
import json
import os
import re
import socketserver
import subprocess
import time
from pathlib import Path

PORT = 8765
ROOT = Path(__file__).resolve().parents[3]
TASKS_DIR = Path(r"C:\Users\azrie\.gemini\antigravity-ide\brain\e38ec20e-cc6d-4297-a5e5-2eb5a29c6789\.system_generated\tasks")

# Cache to avoid hammering external APIs
cache = {
    "kaggle": {"status": "Unknown", "last_check": 0},
    "github_release": {"found": False, "assets": [], "last_check": 0},
    "github_ci": {"runs": [], "last_check": 0}
}


def get_gpu_status():
    try:
        cmd = ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw", "--format=csv,noheader,nounits"]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=2)
        if res.returncode == 0 and res.stdout.strip():
            parts = [p.strip() for p in res.stdout.strip().split(",")]
            return {
                "utilization": int(parts[0]),
                "memory_used": int(parts[1]),
                "memory_total": int(parts[2]),
                "temp": int(parts[3]),
                "power": float(parts[4]),
                "online": True
            }
    except Exception as e:
        pass
    return {"utilization": 0, "memory_used": 0, "memory_total": 4096, "temp": 0, "power": 0, "online": False}


def get_local_training_progress():
    # Find latest task log for train_local_gpu
    log_files = sorted(TASKS_DIR.glob("task-*.log"), key=os.path.getmtime, reverse=True)
    target_log = None
    for f in log_files:
        try:
            content = f.read_text(encoding="utf-8", errors="ignore")
            if "Starting training for role:" in content or "train_local_gpu.py" in content:
                target_log = f
                break
        except Exception:
            continue

    if not target_log:
        return {"active": False, "role": "None", "step": 0, "total": 0, "percent": 0, "elapsed": "", "remaining": "", "speed": "", "recent_logs": []}

    try:
        content = target_log.read_text(encoding="utf-8", errors="ignore")
        lines = [l for l in content.splitlines() if l.strip()]
        
        current_role = "None"
        for line in reversed(lines):
            if "Starting training for role:" in line:
                current_role = line.split(":")[-1].strip()
                break

        # Extract tqdm progress
        m = re.findall(r'(\d+)/(\d+)\s+\[([^<]+)<([^,]+),\s*([^\]]+)\]', content)
        step, total, elapsed, remaining, speed = 0, 0, "", "", ""
        pct = 0
        if m:
            last = m[-1]
            step, total = int(last[0]), int(last[1])
            elapsed, remaining, speed = last[2].strip(), last[3].strip(), last[4].strip()
            pct = round((step / total) * 100, 1) if total > 0 else 0

        # Gating results if any
        gates = {}
        for line in lines:
            gm = re.search(r'\[([\w\-]+)\] GATES: test ([\d\.]+) \(.*?\), adv ([\d\.]+) \(.*?\) -> (PASS|FAIL)', line)
            if gm:
                gates[gm.group(1)] = {"test": float(gm.group(2)), "adv": float(gm.group(3)), "passed": gm.group(4) == "PASS"}

        return {
            "active": step < total or total == 0,
            "role": current_role,
            "step": step,
            "total": total,
            "percent": pct,
            "elapsed": elapsed,
            "remaining": remaining,
            "speed": speed,
            "gates": gates,
            "recent_logs": lines[-15:]
        }
    except Exception as e:
        return {"active": False, "error": str(e), "recent_logs": []}


def get_kaggle_status():
    now = time.time()
    if now - cache["kaggle"]["last_check"] < 10:
        return cache["kaggle"]["data"]

    status_str = "RUNNING"
    try:
        kaggle_exe = Path(os.environ.get("USERPROFILE", "")) / "AppData" / "Roaming" / "Python" / "Python313" / "Scripts" / "kaggle.exe"
        if kaggle_exe.exists():
            res = subprocess.run([str(kaggle_exe), "kernels", "status", "hereliesaz/aive-orchestration-specialists"],
                                 capture_output=True, text=True, timeout=5)
            if res.returncode == 0:
                match = re.search(r'status "([^"]+)"', res.stdout)
                if match:
                    status_str = match.group(1).replace("KernelWorkerStatus.", "")
    except Exception as e:
        status_str = f"Error: {e}"

    data = {
        "kernel": "hereliesaz/aive-orchestration-specialists",
        "status": status_str,
        "target": "Qwen2.5-0.5B-Instruct",
        "mode": "multitask (9 roles) + adapters",
        "url": "https://www.kaggle.com/code/hereliesaz/aive-orchestration-specialists"
    }
    cache["kaggle"] = {"data": data, "last_check": now}
    return data


def get_github_status():
    now = time.time()
    if now - cache["github_release"]["last_check"] < 10:
        return cache["github_release"]["data"]

    found = False
    assets = []
    try:
        res = subprocess.run(["curl.exe", "-s", "--max-time", "3", "https://api.github.com/repos/HereLiesAz/aive/releases/tags/orchestration-utilities-v1"],
                             capture_output=True, text=True, timeout=4)
        if res.returncode == 0 and '"status": "404"' not in res.stdout:
            rel = json.loads(res.stdout)
            if "tag_name" in rel:
                found = True
                assets = [{"name": a["name"], "size": a["size"], "url": a["browser_download_url"]} for a in rel.get("assets", [])]
    except Exception:
        pass

    data = {
        "tag": "orchestration-utilities-v1",
        "released": found,
        "assets": assets,
        "url": "https://github.com/HereLiesAz/aive/releases/tag/orchestration-utilities-v1"
    }
    cache["github_release"] = {"data": data, "last_check": now}
    return data


class DashboardHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            payload = {
                "timestamp": time.strftime("%H:%M:%S"),
                "gpu": get_gpu_status(),
                "local_training": get_local_training_progress(),
                "kaggle": get_kaggle_status(),
                "github": get_github_status(),
                "colab": {
                    "assigned_roles": ["memory-query-composer", "handoff-composer", "execution-state-summarizer"],
                    "mode": "adapters",
                    "status": "HOT_STANDBY",
                    "url": "https://colab.research.google.com/github/HereLiesAz/aive/blob/main/aive_orchestration_specialists.ipynb"
                }
            }
            self.wfile.write(json.dumps(payload).encode("utf-8"))
            return

        if self.path in ("/", "/index.html"):
            index_path = Path(__file__).with_name("index.html")
            if index_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(index_path.read_bytes())
                return

        super().do_GET()

    def log_message(self, format, *args):
        # Silence access logs to keep terminal tidy
        pass


def run():
    os.chdir(Path(__file__).parent)
    with socketserver.TCPServer(("", PORT), DashboardHandler) as httpd:
        print(f"Mission Control Dashboard running at http://localhost:{PORT}")
        httpd.serve_forever()


if __name__ == "__main__":
    run()
