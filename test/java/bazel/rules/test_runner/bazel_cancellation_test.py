#!/usr/bin/env python3
# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Prove actual Bazel interruption/timeout cancellation and worker recovery on Linux."""

import argparse
import json
from pathlib import Path
import re
import signal
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bazel", type=Path)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[5]
    proof = args.artifacts.resolve()
    proof.mkdir(parents=True, exist_ok=True)
    base = [str(args.bazel.resolve()), "test",
            "//test/java/bazel/rules/test_runner:BazelCancellationFixture",
            "--enable_persistent_test_runners=JvmTestRunner", "--strategy=TestRunner=worker,local",
            "--worker_max_instances=JvmTestRunner=1", "--worker_verbose", "--worker_sandboxing",
            "--experimental_worker_cancellation", "--local_test_jobs=1", "--jobs=1",
            "--lockfile_mode=off", "--shell_executable=/bin/bash", "--action_env=PATH",
            "--host_action_env=PATH", "--check_direct_dependencies=off",
            "--nocache_test_results", "--test_output=errors"]

    def passing(name):
        with (proof / (name + ".log")).open("w") as output:
            subprocess.run(base + ["--test_timeout=60"], cwd=root, stdout=output,
                           stderr=subprocess.STDOUT, check=True, timeout=120)
        folder = root / "bazel-testlogs/test/java/bazel/rules/test_runner/BazelCancellationFixture"
        log = (folder / "test.log").read_text()
        (proof / (name + ".test.log")).write_text(log)
        (proof / (name + ".xml")).write_bytes((folder / "test.xml").read_bytes())
        identity = re.search(r"JVM_REUSE target=\S+ suite=\S+ pid=(\d+) "
                             r"token=(\S+) dispatch=(\d+)", log)
        assert identity, log
        return identity.groups()

    passing("warmup")
    results = []
    for kind in ["interrupt", "timeout"]:
        marker = proof / (kind + "-" + str(uuid.uuid4()) + ".pid")
        flags = ["--test_timeout=" + ("2" if kind == "timeout" else "60"),
                 "--test_env=TEST_CANCELLATION_MODE=hang",
                 "--test_env=TEST_CANCELLATION_STARTED=" + str(marker)]
        with (proof / (kind + ".log")).open("w") as output:
            process = subprocess.Popen(base + flags, cwd=root, stdout=output,
                                       stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 120
                while not marker.exists() or marker.stat().st_size == 0:
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Fixture did not start; see " + kind + ".log")
                    time.sleep(.05)
                pid = int(marker.read_text())
                started = time.monotonic()
                if kind == "interrupt":
                    process.send_signal(signal.SIGINT)
                code = process.wait(timeout=30)
                while Path("/proc/" + str(pid)).exists() and time.monotonic() - started < 15:
                    time.sleep(.05)
                assert not Path("/proc/" + str(pid)).exists(), f"Cancelled child {pid} survived"
                assert code == (8 if kind == "interrupt" else 3), code
                if kind == "timeout":
                    assert "TIMEOUT" in (proof / (kind + ".log")).read_text()
                cleanup_seconds = time.monotonic() - started
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
                    process.wait(timeout=30)
        first = passing(kind + "-recovery-first")
        second = passing(kind + "-recovery-second")
        assert first[:2] == second[:2] and first[0] != str(pid), (first, second, pid)
        assert int(second[2]) == int(first[2]) + 1, (first, second)
        for name in [kind + "-recovery-first", kind + "-recovery-second"]:
            assert "Created new" not in (proof / (name + ".log")).read_text(), name
        result = dict(kind=kind, cancelled_pid=pid, bazel_exit=code,
                      cleanup_seconds=cleanup_seconds, recovery_first=first, recovery_second=second)
        results.append(result)
        print(result, flush=True)
    (proof / "summary.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
