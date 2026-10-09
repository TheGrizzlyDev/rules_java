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
"""Small ordinary-Bazel versus retained-JVM benchmark using Bazel's own tests."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bazel", type=Path)
    parser.add_argument("bazel_checkout", type=Path)
    parser.add_argument("--output-base", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.repetitions <= 10:
        parser.error("use between 1 and 10 repetitions to keep this benchmark small")
    binary = args.bazel.resolve()
    checkout = args.bazel_checkout.resolve()
    artifacts = args.artifacts.resolve()
    artifacts.mkdir(parents=True, exist_ok=True)
    rules_java = Path(__file__).resolve().parents[5]
    targets = [
        "//src/test/java/com/google/devtools/build/lib/collect:CollectionUtilsTest",
        "//src/test/java/com/google/devtools/build/lib/collect:ExtremaTest",
        "//src/test/java/com/google/devtools/build/lib/collect:ImmutableSharedKeyMapTest",
    ]
    startup = [str(binary), "--output_base=" + str(args.output_base.resolve())]
    common = targets + [
        "--override_module=rules_java=" + str(rules_java),
        "--extra_toolchains=@rules_java//java/bazel/rules/test_runner:persistent_test_runner_toolchain",
        "--repo_env=ANDROID_HOME=", "--lockfile_mode=off",
        "--shell_executable=/bin/bash", "--action_env=PATH", "--host_action_env=PATH",
        "--check_direct_dependencies=off", "--jobs=1", "--local_test_jobs=1",
        "--worker_max_instances=JvmTestRunner=1", "--worker_verbose",
    ]

    def command(name, verb, flags):
        argv = startup + [verb] + common + flags
        if verb == "test":
            argv.append("--build_event_json_file=" + str(artifacts / (name + ".events.json")))
        with (artifacts / (name + ".command.json")).open("w") as output:
            json.dump(argv, output, indent=2)
        started = time.monotonic()
        with (artifacts / (name + ".log")).open("w") as output:
            subprocess.run(argv, cwd=checkout, stdout=output, stderr=subprocess.STDOUT,
                           check=True, timeout=1800 if verb == "build" else 120)
        return time.monotonic() - started

    ordinary = ["--@rules_java//java/bazel/rules/test_runner:enabled=false",
                "--enable_persistent_test_runners=-JvmTestRunner",
                "--strategy=TestRunner=standalone"]
    persistent = ["--@rules_java//java/bazel/rules/test_runner:enabled=true",
                  "--enable_persistent_test_runners=JvmTestRunner",
                  "--strategy=TestRunner=worker,local"]
    test_flags = ["--nocache_test_results", "--test_timeout=60", "--test_output=errors"]
    # Exclude compilation, server startup, and an ordinary execution warmup from timings.
    # Clear retained workers through Bazel's lifecycle option, keeping action caches intact.
    command("build", "build", ordinary + ["--jobs=4", "--worker_quit_after_build"])
    command("persistent-build", "build", persistent + ["--jobs=4"])
    command("ordinary-warmup", "test", ordinary + test_flags)
    results = []

    def sample(name, mode):
        elapsed = command(name, "test", (persistent if mode == "persistent" else ordinary)
                          + test_flags)
        suites = []
        for target in targets:
            package, label = target[2:].split(":")
            folder = checkout / "bazel-testlogs" / package / label
            output = (folder / "test.log").read_text()
            xml = (folder / "test.xml").read_bytes()
            (artifacts / (name + "." + label + ".log")).write_text(output)
            (artifacts / (name + "." + label + ".xml")).write_bytes(xml)
            cases = list(ET.fromstring(xml).iter("testcase"))
            assert cases and all(case.find("failure") is None and case.find("error") is None
                                 for case in cases), target
            identity = re.search(r"JVM_REUSE target=\S+ suite=\S+ pid=(\d+) "
                                 r"token=(\S+) dispatch=(\d+)", output)
            if mode == "persistent":
                assert identity, "Missing reuse identity for " + target
            else:
                assert "persistent-test-runner" not in output and identity is None, (
                    "Ordinary baseline unexpectedly used the persistent coordinator: " + target)
            suites.append(dict(target=target, cases=len(cases),
                               junit_seconds=sum(float(case.get("time", 0)) for case in cases),
                               identity=identity.groups() if identity else None))
        log = (artifacts / (name + ".log")).read_text()
        elapsed_match = re.search(r"Elapsed time: ([\d.]+)s", log)
        events = [json.loads(line) for line in (artifacts / (name + ".events.json")).read_text().splitlines()]
        test_results = [event["testResult"] for event in events if "testResult" in event]
        assert len(test_results) == len(targets), "Missing test result events: " + name
        assert all(result["status"] == "PASSED" and not result.get("cachedLocally", False)
                   for result in test_results), test_results
        durations = [float(result["testAttemptDuration"].removesuffix("s"))
                     for result in test_results]
        result = dict(name=name, mode=mode, wall_seconds=elapsed,
                      bazel_seconds=float(elapsed_match.group(1)),
                      test_execution_seconds=sum(durations), suites=suites)
        results.append(result)
        print(name, "wall", round(elapsed, 3), "test execution",
              result["test_execution_seconds"], flush=True)

    sample("persistent-cold", "persistent")
    for iteration in range(args.repetitions):
        order = ["ordinary", "persistent"] if iteration % 2 == 0 else ["persistent", "ordinary"]
        for mode in order:
            sample(f"{mode}-{iteration + 1}", mode)

    retained = [suite["identity"] for result in results if result["mode"] == "persistent"
                for suite in result["suites"]]
    assert len({(pid, token) for pid, token, _ in retained}) == 1, retained
    assert sorted(int(dispatch) for _, _, dispatch in retained) == list(range(1, len(retained) + 1))
    medians = {}
    for mode in ["ordinary", "persistent"]:
        measured = [result for result in results if result["mode"] == mode
                    and result["name"] != "persistent-cold"]
        medians[mode] = {metric: statistics.median(result[metric] for result in measured)
                         for metric in ["wall_seconds", "bazel_seconds", "test_execution_seconds"]}
    summary = dict(targets=targets, repetitions=args.repetitions, results=results, medians=medians,
                   bazel_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                   baseline="Same Bazel binary with persistent toolchain disabled; ordinary Java launcher and standalone tests",
                   concurrency=1, test_result_cache=False)
    (artifacts / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(medians, indent=2))


if __name__ == "__main__":
    main()
