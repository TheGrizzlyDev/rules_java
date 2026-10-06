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
package com.google.devtools.build.java.testrunner;

import com.google.devtools.build.java.testrunner.wire.SessionReady;
import com.google.devtools.build.java.testrunner.wire.SessionStart;
import com.google.devtools.build.java.testrunner.wire.TestFinished;
import com.google.devtools.build.java.testrunner.wire.WireChannel;
import com.google.devtools.build.java.testrunner.wire.WorkerShutdown;
import com.google.devtools.build.java.testrunner.wire.WorkerStart;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Exercises the actual coordinator with child JVMs that stop at different startup phases. */
public final class StartupHandshakeTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("bazel.persistent.startup_timeout_seconds", "1");
    exercise("silent", "waiting for SessionReady");
    exercise("exit", "Child exited with status 23 before SessionReady");
    exercise("bad-message", "Expected SessionReady");
    exercise("delayed-silent", "waiting for SessionReady");
    exercise("success", null);
  }

  private static void exercise(String mode, String expected) throws Exception {
    Path dir = Files.createTempDirectory("handshake-test-");
    String oldTmpdir = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", dir.toString());
    Path configPath = dir.resolve("config");
    List<String> config = new ArrayList<>(Arrays.asList(
        "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
        "main_class=Unused", "driver_main=" + Child.class.getName()));
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      config.add("driver_classpath_entry=" + Paths.get(entry).toAbsolutePath());
    }
    Files.write(configPath, config, StandardCharsets.UTF_8);
    Constructor<PersistentTestRunner> ctor = PersistentTestRunner.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    long started = System.nanoTime();
    try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
      try {
        int result = ctor.newInstance().run(Config.parse(configPath),
            Arrays.asList(mode), capture, capture);
        check(expected == null && result == 0, "Unexpected result: " + result);
      } catch (IOException e) {
        check(expected != null && e.getMessage().contains(expected), e.toString());
        System.out.println("  " + e.getMessage());
      }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      check(elapsed < 5000, "Startup/cleanup hung for " + elapsed + " ms");
      String log = output.toString("UTF-8");
      check(log.contains("DEMO: received SessionStart"), "Did not reach handshake: " + log);
      if (mode.equals("delayed-silent")) {
        check(elapsed < 1600, "Handshake got a separate startup budget: " + elapsed);
      }
      if (mode.equals("success")) {
        check(elapsed >= 1500, "Expected test execution to outlast the startup deadline");
      }
      try (Stream<Path> files = Files.list(dir)) {
        check(files.noneMatch(p -> p.getFileName().toString().startsWith("persistent-test-runner-ipc-")),
            "IPC directory leaked");
      }
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.getName().equals("persistent-test-runner-wire-reader")) {
          thread.join(1000);
          check(!thread.isAlive(), "Wire reader leaked");
        }
      }
      System.out.println("PASS " + mode + " (" + elapsed + " ms); IPC cleaned up");
    } finally {
      System.setProperty("java.io.tmpdir", oldTmpdir);
      Files.deleteIfExists(configPath);
      Files.deleteIfExists(dir);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("delayed-silent")) {
        Thread.sleep(700);
      }
      try (WireChannel channel = new WireChannel(
          Files.newInputStream(Paths.get(System.getenv("BAZEL_PERSISTENT_IPC_C2J"))),
          Files.newOutputStream(Paths.get(System.getenv("BAZEL_PERSISTENT_IPC_J2C"))))) {
        check(channel.read() instanceof SessionStart, "Missing SessionStart");
        System.err.println("DEMO: received SessionStart");
        if (args[0].equals("exit")) {
          System.exit(23);
        }
        if (args[0].equals("bad-message")) {
          channel.send(new TestFinished(0));
          Thread.sleep(10000);
        } else if (!args[0].equals("success")) {
          Thread.sleep(10000);
        } else {
          channel.send(SessionReady.INSTANCE);
          check(channel.read() instanceof WorkerStart, "Missing WorkerStart");
          Thread.sleep(1500); // Test execution must not inherit the startup timeout.
          channel.send(new TestFinished(0));
          check(channel.read() instanceof WorkerShutdown, "Missing WorkerShutdown");
        }
      }
    }
  }
}
