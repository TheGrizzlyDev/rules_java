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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spawns warming-JVM engine processes.
 *
 * <p>The engine binary is resolved via Bazel's {@link Runfiles} library from a runfiles-relative
 * path so nested {@code java_binary} spawning works regardless of what runfiles environment the
 * coordinator itself was launched with.
 *
 * <p>Unlocks HotSpot's WhiteBox API on each engine by injecting {@code -XX:+UnlockDiagnosticVMOptions
 * -XX:+WhiteBoxAPI} via the Bazel wrapper's {@code --jvm_flag=}. If a {@code whiteboxJar} path is
 * supplied, prepends it to the engine's bootclasspath so {@code sun.hotspot.WhiteBox} /
 * {@code jdk.test.whitebox.WhiteBox} is reachable. Without the jar, the flags alone still let the
 * engine's {@code JitEnqueuer.detect} report a proper "not available" warning without a
 * ClassNotFoundException surprise on the operator's console.
 */
final class EngineLauncher {

  private static final Logger logger = Logger.getLogger(EngineLauncher.class.getName());

  private final Runfiles runfiles;
  private final String engineRlocation;
  private final int coordinatorPort;
  private final Path whiteboxJar;

  EngineLauncher(
      Runfiles runfiles, String engineRlocation, int coordinatorPort, Path whiteboxJar) {
    this.runfiles = runfiles;
    this.engineRlocation = engineRlocation;
    this.coordinatorPort = coordinatorPort;
    this.whiteboxJar = whiteboxJar;
  }

  Process spawn(int engineIndex) throws IOException {
    String enginePath = runfiles.rlocation(engineRlocation);
    if (enginePath == null) {
      throw new IOException("engine not in runfiles: " + engineRlocation);
    }
    List<String> argv = new ArrayList<>();
    argv.add(enginePath);
    argv.add("--jvm_flag=-XX:+UnlockDiagnosticVMOptions");
    argv.add("--jvm_flag=-XX:+WhiteBoxAPI");
    if (whiteboxJar != null) {
      argv.add("--jvm_flag=-Xbootclasspath/a:" + whiteboxJar.toAbsolutePath());
    }
    argv.add(Integer.toString(coordinatorPort));
    logger.info("spawning engine " + engineIndex + ": " + argv);
    ProcessBuilder pb = new ProcessBuilder(argv).inheritIO();
    pb.environment().putAll(runfiles.getEnvVars());
    return pb.start();
  }

  /**
   * Resolves the WhiteBox jar path from the {@code WARMUP_WHITEBOX_JAR} env var, or returns null.
   * Logs (loudly) when unset so it's visible when JIT warming is going to be a no-op.
   */
  static Path resolveWhiteboxJar() {
    String value = System.getenv("WARMUP_WHITEBOX_JAR");
    if (value == null || value.isEmpty()) {
      logger.warning(
          "WARMUP_WHITEBOX_JAR unset — engines will class-load-warm only, no JIT. Set it to a"
              + " whitebox.jar path (from a HotSpot test build) to enable.");
      return null;
    }
    Path path = Path.of(value);
    if (!Files.isRegularFile(path)) {
      logger.log(
          Level.WARNING,
          "WARMUP_WHITEBOX_JAR=" + value + " is not a regular file; JIT warming disabled");
      return null;
    }
    logger.info("WhiteBox jar: " + path.toAbsolutePath());
    return path;
  }
}
