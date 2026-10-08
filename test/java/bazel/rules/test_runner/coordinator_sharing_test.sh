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
# Run from a Linux development environment with the custom Bazel binary:
#   bash test/java/bazel/rules/test_runner/coordinator_sharing_test.sh /absolute/path/to/bazel
set -euo pipefail
bazel="${1:?Provide the absolute path to the persistent-test Bazel binary}"
cd "$(dirname "$0")/../../../../.."
log="$(mktemp /tmp/rules-java-coordinator-sharing.XXXXXX)"
echo "Build log: $log"
trap 'if [[ $? -eq 0 ]]; then rm -f "$log"; fi' EXIT
targets=(
  //test/java/bazel/rules/test_runner:CoordinatorSharingFirst
  //test/java/bazel/rules/test_runner:CoordinatorSharingSecond
)
common_args=(
  --enable_persistent_test_runners=JvmTestRunner --strategy=TestRunner=worker,local
  --worker_max_instances=JvmTestRunner=1 --worker_verbose
  --lockfile_mode=off --shell_executable=/bin/bash --action_env=PATH --host_action_env=PATH
  --test_timeout=30
)
# Parallelize compilation, then serialize tests: the native fork currently gives
# tests sharing a coordinator executable the same temporary-directory name.
"$bazel" --batch build "${targets[@]}" "${common_args[@]}" --jobs=4 > "$log" 2>&1 || {
  tail -n 80 "$log"
  exit 1
}
"$bazel" --batch test "${targets[@]}" "${common_args[@]}" \
  --jobs=1 --local_test_jobs=1 --nocache_test_results --test_output=errors >> "$log" 2>&1 || {
  tail -n 80 "$log"
  exit 1
}
python3 - "$log" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

build_log = Path(sys.argv[1]).read_text()
workers = re.findall(r'Created new .* JvmTestRunner worker', build_log)
assert len(workers) == 1, f'Expected one coordinator, got {len(workers)}'
identities = []
for suffix, marker in [('First', 'first'), ('Second', 'second')]:
    output = Path('bazel-testlogs/test/java/bazel/rules/test_runner') / ('CoordinatorSharing' + suffix)
    match = re.search(r'^SHARING ' + marker + r' coordinator=(\d+) child=(\d+)$',
                      (output / 'test.log').read_text(), re.MULTILINE)
    assert match, f'Missing identity for {marker}'
    identities.append(match.groups())
    assert ET.parse(output / 'test.xml').getroot().get('name') == marker, f'Wrong XML for {marker}'
assert identities[0][0] == identities[1][0], f'Coordinators differ: {identities}'
assert identities[0][1] != identities[1][1], f'Expected fresh child JVMs: {identities}'
print(f'PASS two targets shared coordinator {identities[0][0]}, '
      f'with distinct children, request arguments, environments, and XML outputs')
PY
