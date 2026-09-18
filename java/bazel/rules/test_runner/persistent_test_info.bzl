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
"""Helper that (optionally) emits Bazel's `PersistentTestInfo` provider.

On Bazel builds that carry the Persistent Test Runners (PTR) API, the
provider is exposed as `testing.PersistentTestInfo`. On builds without PTR,
the attribute is absent and `hasattr(testing, "PersistentTestInfo")` is
False, so we skip emission and the rule still loads cleanly.
"""

# Fixed mnemonic used by `java_test` for `--enable_persistent_test_runners`.
PERSISTENT_TEST_WORKER_KEY_MNEMONIC = "JvmTestRunner"

def persistent_test_info_providers(ctx):
    """Returns a (possibly empty) list of providers to append to a java_test's return value.

    On a PTR-capable Bazel, emits a `PersistentTestInfo` carrying:
      * multiplex = False
      * requires_worker_protocol = "proto"
      * worker_key_mnemonic = PERSISTENT_TEST_WORKER_KEY_MNEMONIC
      * arguments = []  (per-test args baked into the wrapper's config .pb)
      * worker_executable = ctx.outputs.executable
      * test_inputs = []  (runfiles flow via DefaultInfo)
    On stock Bazel (no PTR), returns [].
    """
    if not hasattr(testing, "PersistentTestInfo"):
        return []
    return [
        testing.PersistentTestInfo(
            ctx = ctx,
            multiplex = False,
            requires_worker_protocol = "proto",
            worker_key_mnemonic = PERSISTENT_TEST_WORKER_KEY_MNEMONIC,
            arguments = [],
            worker_executable = ctx.outputs.executable,
            test_inputs = [],
        ),
    ]
