// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.rules_java.warmup.engine;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enqueues methods for JIT compilation at a specific tier.
 *
 * <p>Backed by HotSpot's {@code WhiteBox} API when available (requires {@code
 * -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI} and {@code whitebox.jar} on the bootclasspath).
 * If those aren't present, {@link #detect(Logger)} returns a no-op enqueuer so the engine still
 * does class-load warming. There is intentionally no reflective-invocation fallback: forcing tier-
 * up by calling arbitrary methods requires synthesizing valid arguments, which is infeasible for a
 * profile pulled from JFR samples.
 */
interface JitEnqueuer {

  /** Attempts to compile {@code method} at {@code tier}. Best-effort; failures are logged. */
  void enqueue(Method method, int tier);

  /** True if this enqueuer is expected to actually issue compile requests. */
  boolean isActive();

  /**
   * Returns a WhiteBox-backed enqueuer if the API is reachable in this JVM, else a no-op enqueuer.
   * Logs which one was chosen (loudly, since it affects what warmup actually accomplishes).
   */
  static JitEnqueuer detect(Logger logger) {
    try {
      WhiteBoxJitEnqueuer w = WhiteBoxJitEnqueuer.tryCreate();
      logger.info("JIT enqueuer: WhiteBox API detected; hot-method compilation enabled");
      return w;
    } catch (ReflectiveOperationException | LinkageError e) {
      logger.log(
          Level.WARNING,
          "JIT enqueuer: WhiteBox API not available — class-load warming only, no JIT."
              + " Add -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI and whitebox.jar on the"
              + " bootclasspath to enable.",
          e);
      return new NoopJitEnqueuer();
    }
  }

  /** Fallback used when WhiteBox isn't wired up. Records but does not compile. */
  final class NoopJitEnqueuer implements JitEnqueuer {
    @Override
    public void enqueue(Method method, int tier) {
      // intentionally empty
    }

    @Override
    public boolean isActive() {
      return false;
    }
  }
}
