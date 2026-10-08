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

Our wiring:

- worker_executable → coordinator (jvm_test_toolchain.runner). Stable across
  all java_test targets sharing this toolchain, so it's a constant part of
  the WorkerKey.
- worker_tools → extension jars + aggregated extensions.txt (currently also
  includes ordinary scanned jars; stabilizing this identity is separate work).
- test_args → `--config=<execpath>` for this target's execution-path config.
  Bazel materializes these arguments in a per-test paramfile.
- test_inputs → config, classpaths, extensions, and the original test's runfiles
  files, including its Java runtime and data dependencies.
"""

# Fixed mnemonic used by `java_test` for `--enable_persistent_test_runners`.
PERSISTENT_TEST_WORKER_KEY_MNEMONIC = "JvmTestRunner"

def persistent_test_info_providers(ctx, jvm_test_toolchain, ptr_state, runfiles):
    """Returns a (possibly empty) list containing a PersistentTestInfo provider.

    Args:
      ctx: the java_test rule ctx.
      jvm_test_toolchain: the resolved jvm_persistent_test_runner_info toolchain
        (has .runner and .additional_deps).
      ptr_state: struct produced by _create_test_runner_wrapper, with fields
        {config, classpath, additional_jars, extensions_file, extension_jars}.
      runfiles: the original java_test runfiles before executable substitution.

    """
    if not hasattr(testing, "PersistentTestInfo"):
        return []

    worker_tools_direct = []
    if ptr_state.extensions_file:
        worker_tools_direct.append(ptr_state.extensions_file)
    worker_tools = depset(direct = worker_tools_direct, transitive = [ptr_state.extension_jars])

    # TestStrategy itself wraps test_args in a paramfile. Forcing another
    # paramfile here leaves an unexpanded @arg in single-shot execution.
    test_args = ctx.actions.args()
    # Separate the option and file so test-setup.sh can make the file absolute
    # before changing cwd when persistent execution is disabled.
    test_args.add("--config")
    test_args.add(ptr_state.config)

    return [
        testing.PersistentTestInfo(
            ctx = ctx,
            multiplex = False,
            requires_worker_protocol = "proto",
            worker_key_mnemonic = PERSISTENT_TEST_WORKER_KEY_MNEMONIC,
            test_args = [test_args],
            worker_executable = jvm_test_toolchain.runner[DefaultInfo].files_to_run,
            test_inputs = depset(
                direct = [ptr_state.config],
                transitive = [runfiles.files, ptr_state.classpath, ptr_state.additional_jars, worker_tools],
            ),
            worker_tools = worker_tools,
        ),
    ]
