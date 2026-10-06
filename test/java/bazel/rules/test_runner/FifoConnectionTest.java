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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Real-process/FIFO regression tests; also runnable directly as a short startup demo. */
public final class FifoConnectionTest {
  public static void main(String[] args) throws Exception {
    failure("missing-driver", 30000, "Child exited with status 1");
    failure("no-connect", 500, "Timed out after 500 ms");
    failure("half-connect", 1000, "Timed out after 1000 ms");
    failure("exit", 0, "Child exited with status 23");
    interruption();
    success();
  }

  private static void failure(String mode, long deadline, String expected) throws Exception {
    try (Rig rig = new Rig(mode)) {
      long start = System.nanoTime();
      try (WireChannel ignored = FifoConnection.open(rig.child, rig.c2j, rig.j2c, deadline)) {
        throw new AssertionError("Expected failure for " + mode);
      } catch (IOException e) {
        check(e.getMessage().contains(expected), e.toString());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        check(elapsed < 15000, "Failure took too long: " + elapsed + " ms");
        check(rig.child.waitFor(1, TimeUnit.SECONDS), "Child still running after failure");
        checkNoOpener();
        if (mode.equals("half-connect")) {
          check(Files.exists(rig.dir.resolve("half-open")), "Second FIFO open was not exercised");
        }
        System.out.println("PASS " + mode + ": " + e.getMessage() + " (" + elapsed + " ms)");
      }
    }
  }

  private static void interruption() throws Exception {
    try (Rig rig = new Rig("no-connect")) {
      Thread caller = Thread.currentThread();
      Thread interrupter = new Thread(() -> {
        try {
          Thread.sleep(200);
          caller.interrupt();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      interrupter.start();
      try (WireChannel ignored = FifoConnection.open(rig.child, rig.c2j, rig.j2c, 30000)) {
        throw new AssertionError("Expected interruption");
      } catch (InterruptedException expected) {
        check(rig.child.waitFor(1, TimeUnit.SECONDS), "Interrupted child leaked");
        checkNoOpener();
        System.out.println("PASS interruption: child and FIFO opener cleaned up");
      } finally {
        interrupter.join();
      }
    }
  }

  private static void success() throws Exception {
    try (Rig rig = new Rig("connect");
        WireChannel channel = FifoConnection.open(rig.child, rig.c2j, rig.j2c, 30000)) {
      channel.send(SessionStart.INSTANCE);
      check(channel.read() instanceof SessionReady, "Missing child response");
      check(rig.child.waitFor(2, TimeUnit.SECONDS), "Connected child failed to exit");
      check(rig.child.exitValue() == 0, "Connected child failed");
      checkNoOpener();
      System.out.println("PASS connect: FIFO connection and framed message round trip");
    }
  }

  private static void checkNoOpener() throws InterruptedException {
    // CompletableFuture completion can precede the opener's final few instructions.
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals("persistent-test-runner-fifo-open")) {
        thread.join(1000);
        check(!thread.isAlive(), "FIFO opener leaked");
      }
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static final class Rig implements AutoCloseable {
    final Path dir = Files.createTempDirectory("fifo-startup-test-");
    final Path c2j = dir.resolve("c2j.pipe");
    final Path j2c = dir.resolve("j2c.pipe");
    final Process child;

    Rig(String mode) throws Exception {
      check(new ProcessBuilder("mkfifo", c2j.toString(), j2c.toString()).start().waitFor() == 0,
          "mkfifo failed");
      child = new ProcessBuilder(
          Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", System.getProperty("java.class.path"),
          mode.equals("missing-driver") ? "MissingJvmDriver" : Child.class.getName(),
          mode, c2j.toString(), j2c.toString(), dir.toString()).inheritIO().start();
      // Short connection deadlines should exercise IPC, not cold JVM startup.
      if (!mode.equals("missing-driver") && !mode.equals("exit")) {
        long started = System.nanoTime();
        while (!Files.exists(dir.resolve("ready"))) {
          check(child.isAlive(), "Fixture child exited before readiness");
          check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10),
              "Fixture JVM startup exceeded 10 seconds");
          Thread.sleep(10);
        }
      }
    }

    @Override
    public void close() throws Exception {
      child.destroyForcibly();
      check(child.waitFor(2, TimeUnit.SECONDS), "Child leaked");
      Files.deleteIfExists(c2j);
      Files.deleteIfExists(j2c);
      Files.deleteIfExists(dir.resolve("ready"));
      Files.deleteIfExists(dir.resolve("half-open"));
      Files.deleteIfExists(dir);
    }
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("exit")) {
        System.exit(23);
      }
      Files.createFile(Paths.get(args[3], "ready"));
      if (args[0].equals("no-connect")) {
        Thread.sleep(10000);
        return;
      }
      try (InputStream in = Files.newInputStream(Paths.get(args[1]))) {
        if (args[0].equals("half-connect")) {
          Files.createFile(Paths.get(args[3], "half-open"));
          Thread.sleep(10000);
          return;
        }
        try (OutputStream out = Files.newOutputStream(Paths.get(args[2]));
            WireChannel channel = new WireChannel(in, out)) {
          check(channel.read() instanceof SessionStart, "Missing coordinator message");
          channel.send(SessionReady.INSTANCE);
        }
      }
    }
  }
}
