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
- worker_tools → extension jars + aggregated extensions.txt. The coordinator
  loads the classes named in extensions.txt from these jars at startup, so a
  change must fork a new worker. Same extension set across targets → same
  WorkerKey → one coordinator serves them all.
- test_args → `--config=<execpath>` for this target's config.pb. Delivered as
  a paramfile per WorkRequest.
- test_inputs → extension jars + extensions.txt (declared alongside
  worker_tools so the metadata provider can resolve every tool).
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

    """
    if not hasattr(testing, "PersistentTestInfo"):
        return []

    worker_tools_direct = []
    if ptr_state.extensions_file:
        worker_tools_direct.append(ptr_state.extensions_file)
    worker_tools = depset(direct = worker_tools_direct, transitive = [ptr_state.extension_jars])

    # Bazel's worker strategy requires exactly one @flagfile in the spawn argv.
    # We satisfy that by putting our real argv (--config=<exec-root-relative
    # path to the target's config.pb>) into an Args object; Bazel materialises
    # it as a paramfile and passes @paramfile on argv. Exec path (not runfiles
    # short_path) because in worker mode the coordinator runs at exec root and
    # per-target files are staged as spawn inputs, not runfiles of the worker.
    test_args = ctx.actions.args()
    test_args.use_param_file("@%s", use_always = True)
    test_args.set_param_file_format("multiline")
    test_args.add(ptr_state.config, format = "--config=%s")

    return [
        testing.PersistentTestInfo(
            ctx = ctx,
            multiplex = False,
            requires_worker_protocol = "proto",
            worker_key_mnemonic = PERSISTENT_TEST_WORKER_KEY_MNEMONIC,
            test_args = [test_args],
            worker_executable = jvm_test_toolchain.runner[DefaultInfo].files_to_run,
            test_inputs = worker_tools,
            worker_tools = worker_tools,
        ),
    ]
