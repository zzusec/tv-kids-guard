"""Offline regressions for the optional ADB guard; never connects to a television."""

import os
import plistlib
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent / "tvguard"


class TvGuardScriptsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tvguard-shell-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        self.state = self.directory / "state"
        self.env = dict(os.environ, PATH=f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin",
                        TVGUARD_DIR=str(self.directory), TVGUARD_SERIAL="offline-test-device")

    def executable(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def run_daemon(self, day="20261109", command=None):
        self.state.write_text(
            f"DAY={day}\nUSED=90\nUNLOCKED=1\nBONUS_MINUTES=20\nENABLED=1\n",
            encoding="utf-8")
        if command:
            (self.directory / "cmd").write_text(command + "\n", encoding="utf-8")
        self.executable("date", "printf '20261110\\n'\n")
        self.executable("dumpsys", "printf 'Display Power: state=OFF\\n'\n")
        # Exit via the supported stop command after one complete tick.
        self.executable("sleep", "printf 'stop\\n' > \"$TVGUARD_DIR/cmd\"\n")
        result = subprocess.run(["sh", str(ROOT / "tvguard-daemon.sh")],
                                env=self.env, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return dict(line.split("=", 1) for line in self.state.read_text().splitlines())

    def test_new_day_clears_yesterdays_temporary_bonus(self):
        state = self.run_daemon()
        self.assertEqual("20261110", state["DAY"])
        self.assertEqual("0", state["USED"])
        self.assertEqual("0", state["UNLOCKED"])
        self.assertEqual("0", state["BONUS_MINUTES"])

    def test_command_received_on_new_day_applies_after_rollover(self):
        state = self.run_daemon(command="unlock")
        self.assertEqual("1", state["UNLOCKED"])
        state = self.run_daemon(command="bonus 5")
        self.assertEqual("5", state["BONUS_MINUTES"])

    def test_same_day_and_clock_rollback_preserve_temporary_bonus(self):
        for day in ("20261110", "20261111"):
            with self.subTest(day=day):
                state = self.run_daemon(day)
                self.assertEqual(day, state["DAY"])
                self.assertEqual("20", state["BONUS_MINUTES"])

    def test_watchdog_launch_agent_carries_the_resolved_adb_path(self):
        self.bin = self.directory / "bin & tools"
        self.bin.mkdir()
        self.env["PATH"] = f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin"
        self.executable("adb", "exit 0\n")
        self.executable("launchctl", "exit 0\n")
        home = self.directory / "home"
        home.mkdir()
        result = subprocess.run(["bash", str(ROOT / "tvguardctl"), "install-watchdog"],
                                env=dict(self.env, HOME=str(home)), capture_output=True,
                                text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        plist = home / "Library/LaunchAgents/com.hx10.tvguard.watchdog.plist"
        with plist.open("rb") as stream:
            config = plistlib.load(stream)
        environment = config["EnvironmentVariables"]
        self.assertEqual(str(self.bin / "adb"), environment.get("TVGUARD_ADB"))
        # launchd does not inherit the terminal PATH containing Homebrew's adb.
        self.executable("adb", "if [ \"$3\" = get-state ]; then printf 'device\\n'; fi\n")
        service_env = {"HOME": str(home), "PATH": "/usr/bin:/bin:/usr/sbin:/sbin", **environment}
        result = subprocess.run(["/bin/bash", *config["ProgramArguments"]], env=service_env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_watchdog_load_failure_returns_error(self):
        self.executable("adb", "exit 0\n")
        self.executable("launchctl", "exit 7\n")
        home = self.directory / "home"
        home.mkdir()
        result = subprocess.run(["bash", str(ROOT / "tvguardctl"), "install-watchdog"],
                                env=dict(self.env, HOME=str(home)), capture_output=True,
                                text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("已安装看护任务", result.stdout)
        self.assertIn("error:", result.stderr)

    def test_adb_write_failure_never_reports_command_or_config_success(self):
        self.executable("adb", '''
case "$3" in
    get-state) printf 'device\\n'; exit 0 ;;
    shell) printf 'offline write failed\\n' >&2; exit 7 ;;
esac
exit 7
''')
        for command in (("bonus", "5"), ("lock",), ("limit", "30")):
            with self.subTest(command=command):
                result = subprocess.run(["bash", str(ROOT / "tvguardctl"), *command],
                                        env=self.env, capture_output=True, text=True, timeout=5)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertNotIn("已发送", result.stdout)
                self.assertNotIn("会在 10 秒内", result.stdout)
                self.assertIn("error:", result.stderr)


if __name__ == "__main__":
    unittest.main()
