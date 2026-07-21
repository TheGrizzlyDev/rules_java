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
package com.google.devtools.build.rules_java.warmup.coordinator;

import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Spawns warming-JVM engine processes.
 *
 * <p>The engine binary is resolved via Bazel's {@link Runfiles} library from a runfiles-relative
 * path so nested {@code java_binary} spawning works regardless of what runfiles environment the
 * coordinator itself was launched with.
 */
final class EngineLauncher {

  private static final Logger logger = Logger.getLogger(EngineLauncher.class.getName());

  private final Runfiles runfiles;
  private final String engineRlocation;
  private final int coordinatorPort;

  EngineLauncher(Runfiles runfiles, String engineRlocation, int coordinatorPort) {
    this.runfiles = runfiles;
    this.engineRlocation = engineRlocation;
    this.coordinatorPort = coordinatorPort;
  }

  Process spawn(int engineIndex) throws IOException {
    String enginePath = runfiles.rlocation(engineRlocation);
    if (enginePath == null) {
      throw new IOException("engine not in runfiles: " + engineRlocation);
    }
    List<String> argv = new ArrayList<>(2);
    argv.add(enginePath);
    argv.add(Integer.toString(coordinatorPort));
    logger.info("spawning engine " + engineIndex + ": " + argv);
    ProcessBuilder pb = new ProcessBuilder(argv).inheritIO();
    pb.environment().putAll(runfiles.getEnvVars());
    return pb.start();
  }
}
