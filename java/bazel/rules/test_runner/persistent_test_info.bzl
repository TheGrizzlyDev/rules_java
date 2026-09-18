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
"""Emits Bazel's `PersistentTestInfo` provider when the running Bazel supports it.

On PTR-capable Bazel the provider is exposed as `testing.PersistentTestInfo`.
On other builds the attribute is absent and we skip emission so the rule
still loads cleanly.

Split of concerns (matches Bazel's WorkerKey semantics):

- worker_executable: the coordinator binary (jvm_test_toolchain.runner). Stable
  across all java_test targets that share the same toolchain, so the WorkerKey
  contribution from the executable is a constant.
- tools: extension jars + the aggregated extensions.txt. These affect the
  coordinator's runtime behaviour (the runner loads the classes named in
  extensions.txt from the extension jars), so a change here must fork a new
  worker. Different targets with different extension sets get different
  WorkerKeys; same extensions → same WorkerKey → shared coordinator.
- test_inputs: the classpath jars + config.pb. Change per target, but do NOT
  invalidate the worker — they arrive as spawn inputs per WorkRequest.
"""

# Fixed mnemonic used by `java_test` for `--enable_persistent_test_runners`.
PERSISTENT_TEST_WORKER_KEY_MNEMONIC = "JvmTestRunner"

def persistent_test_info_providers(ctx, jvm_test_toolchain, ptr_state):
    """Returns a (possibly empty) list containing a PersistentTestInfo provider.

    Args:
      ctx: the java_test rule ctx.
      jvm_test_toolchain: the resolved jvm_persistent_test_runner_info toolchain
        (has .runner and .additional_deps).
      ptr_state: struct produced by _create_test_runner_wrapper, with fields
        {config, classpath, additional_jars, extensions_file, extension_jars}.

    Wiring rationale (see StandaloneTestStrategy):

    - worker_executable = jvm_test_toolchain.runner. Its FilesToRun is stable
      across all java_test targets that share the toolchain, so it doesn't drift
      per target.
    - tools = extension jars + extensions.txt. These are the only per-target
      files that must affect WorkerKey (they change what classes the coordinator
      loads at startup). When they match across targets, targets share a worker.
    - test_inputs = same set as tools. Bazel's worker strategy hashes each tool
      via the spawn's InputMetadataProvider, so every tool must also be a spawn
      input. Declaring the same depset in both fields is intentional (and cheap
      — NestedSet dedupes on ref).
    """
    if not hasattr(testing, "PersistentTestInfo"):
        return []

    tools_direct = []
    if ptr_state.extensions_file:
        tools_direct.append(ptr_state.extensions_file)
    tools = depset(direct = tools_direct, transitive = [ptr_state.extension_jars])

    # Bazel's worker strategy requires exactly one @flagfile in the spawn argv.
    # We satisfy that by putting our real argv (--config=<exec-root-relative
    # path to the target's config.pb>) into an Args object; Bazel materialises
    # it as a paramfile and passes @paramfile on argv. Exec path (not runfiles
    # short_path) because in worker mode the coordinator runs at exec root and
    # per-target files are staged as spawn inputs, not runfiles of the worker.
    args = ctx.actions.args()
    args.use_param_file("@%s", use_always = True)
    args.set_param_file_format("multiline")
    args.add(ptr_state.config, format = "--config=%s")

    return [
        testing.PersistentTestInfo(
            ctx = ctx,
            multiplex = False,
            requires_worker_protocol = "proto",
            worker_key_mnemonic = PERSISTENT_TEST_WORKER_KEY_MNEMONIC,
            arguments = [args],
            worker_executable = jvm_test_toolchain.runner[DefaultInfo].files_to_run,
            test_inputs = tools,
            tools = tools,
        ),
    ]
