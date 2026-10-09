#!/usr/bin/env bash
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
# Usage: bash child_reuse_test.sh /absolute/custom/bazel [/absolute/bazel/checkout]
# Logs, copied XML, and a machine-readable summary are retained as proof artifacts.
set -euo pipefail
export PATH="${JVM_REUSE_ACTION_PATH:-$PATH}"
bazel="${1:?Provide the absolute path to the persistent-test Bazel binary}"
rules_java="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
proof="${JVM_REUSE_PROOF_DIR:-$(mktemp -d /tmp/rules-java-jvm-reuse.XXXXXX)}"
mkdir -p "$proof"
echo "Reuse proof artifacts: $proof"
common=(
  --enable_persistent_test_runners=JvmTestRunner --strategy=TestRunner=worker,local
  --worker_max_instances=JvmTestRunner=1 --worker_verbose --local_test_jobs=1
  --lockfile_mode=off --shell_executable=/bin/bash --action_env=PATH --host_action_env=PATH
  --test_timeout=60 --check_direct_dependencies=off
)
if [[ "${JVM_REUSE_MANIFEST_ONLY:-0}" == 1 ]]; then
  common+=(--enable_runfiles=no)
fi
verify() {
  python3 - "$proof" "$1" "${@:2}" <<'PYPROOF'
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

proof, group, *targets = sys.argv[1:]
proof = Path(proof)
log = (proof / (group + '.build.log')).read_text()
assert len(re.findall(r'Created new .* JvmTestRunner worker', log)) == 1, 'Expected one coordinator'
results = []
for target in targets:
    package, name = target[2:].split(':')
    folder = Path('bazel-testlogs') / package / name
    output = (folder / 'test.log').read_text()
    match = re.search(r'JVM_REUSE target=' + re.escape(target)
                      + r' suite=(\S+) pid=(\d+) token=(\S+) dispatch=(\d+)', output)
    assert match, f'Missing child identity for {target}: {output}'
    suite, pid, token, dispatch = match.groups()
    xml = ET.parse(folder / 'test.xml')
    cases = list(xml.getroot().iter('testcase'))
    assert cases and any(case.get('classname') == suite for case in cases), f'Wrong XML suite for {target}'
    assert all(case.find('failure') is None and case.find('error') is None for case in cases), target
    (proof / (group + '.' + name + '.log')).write_text(output)
    (proof / (group + '.' + name + '.xml')).write_bytes((folder / 'test.xml').read_bytes())
    results.append(dict(target=target, suite=suite, pid=pid, token=token,
                        dispatch=int(dispatch), tests=len(cases)))
assert len({result['pid'] for result in results}) == 1, results
assert len({result['token'] for result in results}) == 1, results
assert len({result['dispatch'] for result in results}) == len(targets), results
if group == 'rules_java':
    states = []
    for result in results:
        name = result['target'].split(':')[1]
        if not name.startswith('WarmReuse'):
            continue
        output = (proof / (group + '.' + name + '.log')).read_text()
        match = re.search(r'WARM_STATE marker=\S+ token=(\S+) count=(\d+)', output)
        assert match, output
        states.append(match.groups())
        context = re.search(r'RUNFILES_CONTEXT expected=(first|second) retained=(\d+)', output)
        assert context, f'Missing per-target runfiles proof: {output}'
        process = re.search(r'PROCESS_CONTEXT cwd=.+ marker=(first|second)', output)
        assert process, f'Missing relative I/O and inherited subprocess proof: {output}'
    assert len({token for token, _ in states}) == 1, states
    assert sorted(int(count) for _, count in states) == [1, 2], states
summary = proof / 'summary.json'
data = json.loads(summary.read_text()) if summary.exists() else {}
data[group] = results
summary.write_text(json.dumps(data, indent=2) + '\n')
print(f'PASS {group}: {len(targets)} distinct targets, {sum(r["tests"] for r in results)} tests, '
      f'child PID {results[0]["pid"]}, startup token {results[0]["token"]}')
PYPROOF
}
cd "$rules_java"
targets=(
  //test/java/bazel/rules/test_runner:WarmReuseFirst
  //test/java/bazel/rules/test_runner:WarmReuseSecond
  //test/java/bazel/rules/test_runner/wire:CodecTest
)
# Match this repo's test-only options during build to retain the tool action cache.
repo_settings=(--nointerface_shared_objects --incompatible_java_info_merge_runtime_module_flags)
"$bazel" --batch build "${targets[@]}" "${common[@]}" "${repo_settings[@]}" --jobs=4 > "$proof/rules_java.build.log" 2>&1 || {
  tail -n 100 "$proof/rules_java.build.log"; exit 1
}
"$bazel" --batch test "${targets[@]}" "${common[@]}" "${repo_settings[@]}" \
  --jobs=1 --nocache_test_results --test_output=errors >> "$proof/rules_java.build.log" 2>&1 || {
  tail -n 100 "$proof/rules_java.build.log"; exit 1
}
verify rules_java "${targets[@]}"
if [[ $# -ge 2 ]]; then
  export PATH="${BAZEL_REUSE_ACTION_PATH:-$PATH}"
  cd "$2"
  # Isolate the test invocation from any source-build server. Set this to an
  # existing isolated output base when retaining its action cache is useful.
  startup=(--batch --output_base="${BAZEL_REUSE_OUTPUT_BASE:-/tmp/rules-java-bazel-reuse-output}")
  native=(--override_module="rules_java=$rules_java"
    --extra_toolchains=@rules_java//java/bazel/rules/test_runner:persistent_test_runner_toolchain
    --repo_env=ANDROID_HOME=)
  targets=(
    //src/test/java/com/google/devtools/build/lib/collect:CollectionUtilsTest
    //src/test/java/com/google/devtools/build/lib/collect:ExtremaTest
    //src/test/java/com/google/devtools/build/lib/collect:ImmutableSharedKeyMapTest
  )
  "$bazel" "${startup[@]}" build "${targets[@]}" "${common[@]}" "${native[@]}" \
    --jobs=4 > "$proof/bazel.build.log" 2>&1 || {
    tail -n 100 "$proof/bazel.build.log"; exit 1
  }
  "$bazel" "${startup[@]}" test "${targets[@]}" "${common[@]}" "${native[@]}" \
    --jobs=1 --nocache_test_results --test_output=errors >> "$proof/bazel.build.log" 2>&1 || {
    tail -n 100 "$proof/bazel.build.log"; exit 1
  }
  verify bazel "${targets[@]}"
fi
