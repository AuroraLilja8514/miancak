"""Exercise the real admission CLI in an inert, token-free environment."""
from pathlib import Path
import shlex
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AdmissionCLI(unittest.TestCase):
    def invoke(self, *, mode="publish", expired=False):
        workflow = ROOT / ".github/workflows/protected-preview.yml"
        prefix = "run: python3 tools/protected-preview/preview_ops.py admit"
        commands = [line.strip().removeprefix("run: ") for line in workflow.read_text().splitlines()
                    if line.strip().startswith(prefix)]
        self.assertEqual(len(commands), 1)
        argv = shlex.split(commands[0])
        self.assertEqual(argv, ["python3", "tools/protected-preview/preview_ops.py", "admit",
                                "--timeout-seconds", "75"])
        # Start without inherited tokens or a production workflow identity.
        env = {"PYTHONDONTWRITEBYTECODE": "1", "PREVIEW_MODE": mode,
               "GITHUB_REPOSITORY": "fixture/inert", "GITHUB_REF": "refs/heads/inert"}
        if expired:
            env["MIANCAK_TIMEOUT_DEADLINE"] = "0"
        return subprocess.run([sys.executable, *argv[1:]], cwd=ROOT, env=env,
                              capture_output=True, text=True, timeout=10)

    def test_timeout_option_is_consumed_before_admission_checks(self):
        for mode in ("publish", "approve-inputs"):
            with self.subTest(mode=mode):
                result = self.invoke(mode=mode)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, "")
                self.assertEqual(result.stderr, "FAIL: PREVIEW_WORKFLOW_AUTHORITY\n")

    def test_timeout_option_cannot_extend_an_expired_deadline(self):
        result = self.invoke(expired=True)
        self.assertEqual(result.returncode, 124)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "Timeout: inherited script deadline expired\n")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
