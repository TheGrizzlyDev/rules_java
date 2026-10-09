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
package com.google.devtools.build.java.testrunner.persistent_worker;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * Business logic for a single work unit. The same instance handles sequential requests and may
 * retain state between them. Worker mode runs handlers on a request thread; cancellation interrupts
 * that thread. Implementations must release request-owned resources before returning.
 */
public interface WorkHandler {
  /**
   * Processes one unit of work.
   *
   * @param args post-argfile-expansion arguments (equivalent to argv for a one-shot invocation).
   * @param environment immutable base environment overlaid with this request's variables.
   * @param stdout captures user-facing stdout for this unit; contents surface in {@code
   *     WorkResponse.output}.
   * @param stderr captures user-facing stderr for this unit; contents also surface in {@code
   *     WorkResponse.output}.
   * @return the exit code for this unit (0 on success).
   */
  int handle(
      List<String> args, Map<String, String> environment, PrintStream stdout, PrintStream stderr)
      throws Exception;
}
