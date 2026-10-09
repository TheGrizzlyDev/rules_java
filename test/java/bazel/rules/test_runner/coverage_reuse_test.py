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
"""Check per-test coverage and real JVM reuse against ordinary Bazel coverage."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bazel", type=Path)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--bazel-checkout", type=Path)
    parser.add_argument("--bazel-output-base", type=Path)
    parser.add_argument("--bazel-action-path", help="stable action PATH for the Bazel checkout")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[5]
    proof = args.artifacts.resolve()
    proof.mkdir(parents=True, exist_ok=True)
    binary = str(args.bazel.resolve())
    common = ["--lockfile_mode=off", "--shell_executable=/bin/bash", "--action_env=PATH",
              "--host_action_env=PATH", "--check_direct_dependencies=off", "--jobs=4",
              "--local_test_jobs=1", "--worker_max_instances=JvmTestRunner=1",
              "--nocache_test_results", "--test_timeout=60", "--test_output=errors"]
    ordinary = ["--@rules_java//java/bazel/rules/test_runner:enabled=false",
                "--enable_persistent_test_runners=-JvmTestRunner", "--strategy=TestRunner=standalone"]
    persistent = ["--@rules_java//java/bazel/rules/test_runner:enabled=true",
                  "--enable_persistent_test_runners=JvmTestRunner", "--strategy=TestRunner=worker,local"]
    groups = [("repo", repo, [],
               ["//test/java/com/google/devtools/build/java/testrunner/coverage:" + suite
                for suite in ["First", "Second"]],
               ["--java_runtime_version=remotejdk_21", "--tool_java_runtime_version=remotejdk_21",
                "--//toolchains:incompatible_language_version_bootclasspath",
                "--instrumentation_filter=//test/java/com/google/devtools/build/java/testrunner/coverage"])]
    if args.bazel_checkout:
        if not args.bazel_output_base:
            parser.error("--bazel-output-base is required with --bazel-checkout")
        groups.append(("bazel", args.bazel_checkout.resolve(),
                       ["--output_base=" + str(args.bazel_output_base.resolve())],
                       ["//src/test/java/com/google/devtools/build/lib/collect:" + suite for suite in
                        ["CollectionUtilsTest", "ExtremaTest", "ImmutableSharedKeyMapTest"]],
                       ["--repo_env=ANDROID_HOME=", "--override_module=rules_java=" + str(repo),
                        "--extra_toolchains=@rules_java//java/bazel/rules/test_runner:persistent_test_runner_toolchain",
                        "--instrumentation_filter=//src/main/java/com/google/devtools/build/lib/collect"]))
    summaries = {}
    for group, checkout, startup, targets, flags in groups:
        baseline = {}
        identities = []
        warm_states = []
        reports = []
        for mode in ["ordinary", "persistent", "persistent-repeat"]:
            name = group + "." + mode
            command = [binary] + startup + ["coverage"] + targets + common + flags + (
                ordinary if mode == "ordinary" else persistent)
            (proof / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
            with (proof / (name + ".log")).open("w") as output:
                subprocess.run(command, cwd=checkout, stdout=output, stderr=subprocess.STDOUT,
                               check=True, timeout=900, env=dict(os.environ, PATH=args.bazel_action_path)
                               if group == "bazel" and args.bazel_action_path else None)
            for target in targets:
                package, label = target[2:].split(":")
                folder = checkout / "bazel-testlogs" / package / label
                log = (folder / "test.log").read_text()
                report = (folder / "coverage.dat").read_text()
                prefix = name + "." + label
                (proof / (prefix + ".log")).write_text(log)
                (proof / (prefix + ".dat")).write_text(report)
                (proof / (prefix + ".xml")).write_bytes((folder / "test.xml").read_bytes())
                assert "Permission denied" not in log and "no coverage found" not in log.lower(), log
                lines = sum(map(int, re.findall(r"^LH:(\d+)$", report, re.M)))
                assert lines > 0, "Empty coverage: " + prefix
                identity = re.search(r"JVM_REUSE .* pid=(\d+) token=(\S+) dispatch=(\d+)", log)
                if mode == "ordinary":
                    assert identity is None and "persistent-test-runner" not in log, log
                    baseline[label] = report
                else:
                    assert identity, log
                    identities.append(identity.groups())
                if group == "repo":
                    covered = "firstBranch" if label == "First" else "secondBranch"
                    uncovered = "secondBranch" if label == "First" else "firstBranch"
                    assert re.search(r"(?m)^FNDA:1,.*" + covered + r".*$", report), report
                    assert re.search(r"(?m)^FNDA:0,.*" + uncovered + r".*$", report), report
                    state = re.search(r"COVERAGE_WARM token=(\S+) count=(\d+)", log)
                    assert state, log
                    if mode != "ordinary":
                        warm_states.append(state.groups())
                elif mode != "ordinary":
                    # Class initializers run only once in the retained loader. Other
                    # methods must have the same hit counts as ordinary execution.
                    def functions(text):
                        return {method: int(count) for count, method in
                                re.findall(r"^FNDA:(\d+),(.+)$", text, re.M)
                                if "::<clinit>" not in method}
                    assert functions(report) == functions(baseline[label]), prefix
                    assert re.findall(r"^SF:(.+)$", report, re.M) == re.findall(
                        r"^SF:(.+)$", baseline[label], re.M), prefix
                reports.append(dict(mode=mode, target=target, covered_lines=lines))
        assert len({(pid, token) for pid, token, _ in identities}) == 1, identities
        assert sorted(int(count) for _, _, count in identities) == list(range(1, len(identities) + 1))
        if warm_states:
            assert len({token for token, _ in warm_states}) == 1, warm_states
            assert sorted(int(count) for _, count in warm_states) == list(range(1, len(warm_states) + 1))
        summaries[group] = dict(identity=identities[0][:2], dispatches=len(identities), reports=reports)
        print("PASS", group, len(identities), "covered dispatches in child", identities[0][0], flush=True)
    (proof / "summary.json").write_text(json.dumps(summaries, indent=2) + "\n")


if __name__ == "__main__":
    main()
