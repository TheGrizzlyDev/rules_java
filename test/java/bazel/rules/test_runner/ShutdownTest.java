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
import com.google.devtools.build.java.testrunner.wire.WireChannel;
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

/** Real child processes exercise shutdown after TestFinished and after wire EOF. */
public final class ShutdownTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("bazel.persistent.shutdown_timeout_seconds", "1");
    Constructor<PersistentTestRunner> ctor = PersistentTestRunner.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    PersistentTestRunner runner = ctor.newInstance();
    exercise(runner, "stuck", true, 0);
    exercise(runner, "eof-stuck", true, 0);
    exercise(runner, "failure", false, 1);
    exercise(runner, "success", false, 0);
    exercise(runner, "eof-exit", false, 23);
  }

  private static void exercise(PersistentTestRunner runner, String mode, boolean timeout,
      int expectedExit) throws Exception {
    Path dir = Files.createTempDirectory("shutdown-test-");
    String oldTmpdir = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", dir.toString());
    Path configPath = dir.resolve("config");
    Path pidPath = dir.resolve("pid");
    List<String> config = new ArrayList<>(Arrays.asList(
        "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
        "main_class=" + Child.class.getName(),
        "driver_main=" + (mode.startsWith("eof-") ? EofChild.class.getName()
            : JvmDriver.class.getName())));
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      config.add("driver_classpath_entry=" + Paths.get(entry).toAbsolutePath());
    }
    Files.write(configPath, config, StandardCharsets.UTF_8);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    long started = System.nanoTime();
    try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
      try {
        int result = runner.run(Config.parse(configPath), Arrays.asList(mode, pidPath.toString()),
            capture, capture);
        check(!timeout && result == expectedExit, "Unexpected result: " + result);
      } catch (IOException e) {
        check(timeout && e.getMessage().contains("Child shutdown timed out after 1 seconds"),
            e.toString());
      }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      check(elapsed < 4000, "Shutdown/cleanup hung for " + elapsed + " ms");
      if (timeout) {
        check(elapsed >= 1000, "Shutdown grace was not observed: " + elapsed);
      }
      check(Files.exists(pidPath), "Child never ran: " + output.toString("UTF-8"));
      long pid = Long.parseLong(new String(Files.readAllBytes(pidPath), StandardCharsets.UTF_8));
      check(!Files.exists(Paths.get("/proc", Long.toString(pid))), "Child leaked: " + pid);
      String log = output.toString("UTF-8");
      check(log.contains("FINAL stdout " + mode) && log.contains("FINAL stderr " + mode),
          "Final output lost: " + log);
      if (!timeout && !mode.startsWith("eof-")) {
        check(log.contains("SHUTDOWN stdout " + mode) && log.contains("SHUTDOWN stderr " + mode),
            "Output emitted after TestFinished was lost: " + log);
      }
      try (Stream<Path> files = Files.list(dir)) {
        check(files.noneMatch(p -> p.getFileName().toString().startsWith("persistent-test-runner-ipc-")),
            "IPC directory leaked");
      }
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.getName().startsWith("persistent-test-runner-wire-")
            || thread.getName().equals("child-stdout-drain")
            || thread.getName().equals("child-stderr-drain")) {
          thread.join(1000);
          check(!thread.isAlive(), "Thread leaked: " + thread.getName());
        }
      }
      System.out.println("PASS " + mode + " (" + elapsed + " ms)");
    } finally {
      System.setProperty("java.io.tmpdir", oldTmpdir);
      Files.deleteIfExists(configPath);
      Files.deleteIfExists(pidPath);
      Files.deleteIfExists(dir);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static void record(String[] args) throws Exception {
    String pid = null;
    for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
      if (line.startsWith("Pid:")) {
        pid = line.substring(4).trim();
      }
    }
    check(pid != null, "Could not read child PID");
    Files.write(Paths.get(args[1]), pid.getBytes(StandardCharsets.UTF_8));
    System.out.println("FINAL stdout " + args[0]);
    System.err.println("FINAL stderr " + args[0]);
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      record(args);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          Thread.sleep(args[0].equals("stuck") ? 10000 : 150);
          System.out.println("SHUTDOWN stdout " + args[0]);
          System.err.println("SHUTDOWN stderr " + args[0]);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }));
      if (args[0].equals("failure")) {
        throw new IllegalStateException("Expected test failure");
      }
    }
  }

  public static final class EofChild {
    public static void main(String[] args) throws Exception {
      try (WireChannel channel = new WireChannel(
          Files.newInputStream(Paths.get(System.getenv("BAZEL_PERSISTENT_IPC_C2J"))),
          Files.newOutputStream(Paths.get(System.getenv("BAZEL_PERSISTENT_IPC_J2C"))))) {
        check(channel.read() instanceof SessionStart, "Missing SessionStart");
        channel.send(SessionReady.INSTANCE);
        check(channel.read() instanceof WorkerStart, "Missing WorkerStart");
        record(args);
      }
      if (args[0].equals("eof-stuck")) {
        Thread.sleep(10000);
      }
      System.exit(23);
    }
  }
}
