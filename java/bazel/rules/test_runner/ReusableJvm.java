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

import com.google.devtools.build.java.testrunner.wire.Message;
import com.google.devtools.build.java.testrunner.wire.SessionReady;
import com.google.devtools.build.java.testrunner.wire.SessionStart;
import com.google.devtools.build.java.testrunner.wire.StoreGetRequest;
import com.google.devtools.build.java.testrunner.wire.StoreGetResponse;
import com.google.devtools.build.java.testrunner.wire.StoreSetAck;
import com.google.devtools.build.java.testrunner.wire.StoreSetRequest;
import com.google.devtools.build.java.testrunner.wire.StoreValue;
import com.google.devtools.build.java.testrunner.wire.TestFinished;
import com.google.devtools.build.java.testrunner.wire.TestOutput;
import com.google.devtools.build.java.testrunner.wire.TestStart;
import com.google.devtools.build.java.testrunner.wire.WireChannel;
import com.google.devtools.build.java.testrunner.wire.WorkerShutdown;
import com.google.devtools.build.java.testrunner.wire.WorkerStart;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns one retained process, wire reader, and the jars attached to its warm loader. */
final class ReusableJvm implements AutoCloseable {
  final String startupKey;
  final Process process;
  private final Path directory;
  private final Path c2j;
  private final Path j2c;
  private final WireChannel channel;
  private final Thread reader;
  private final Thread stdoutDrain;
  private final Thread stderrDrain;
  private final CoordinatorStore store;
  private final Map<String, String> jars = new LinkedHashMap<>();
  private final Map<String, String> labels = new LinkedHashMap<>();
  private volatile PrintStream stdout;
  private volatile PrintStream stderr;
  private volatile CompletableFuture<Integer> result;
  private volatile Throwable failure;
  private volatile boolean retiring;

  private ReusableJvm(
      String startupKey,
      Process process,
      Path directory,
      Path c2j,
      Path j2c,
      WireChannel channel,
      Thread stdoutDrain,
      Thread stderrDrain,
      CoordinatorStore store,
      PrintStream stdout,
      PrintStream stderr) {
    this.startupKey = startupKey;
    this.process = process;
    this.directory = directory;
    this.c2j = c2j;
    this.j2c = j2c;
    this.channel = channel;
    this.stdoutDrain = stdoutDrain;
    this.stderrDrain = stderrDrain;
    this.store = store;
    this.stdout = stdout;
    this.stderr = stderr;
    reader = new Thread(this::read, "persistent-test-runner-reusable-reader");
    reader.setDaemon(true);
  }

  static ReusableJvm start(
      String key,
      List<String> command,
      Map<String, String> environment,
      CoordinatorStore store,
      PrintStream stdout,
      PrintStream stderr,
      long timeoutMillis)
      throws IOException, InterruptedException {
    Path directory = Files.createTempDirectory("persistent-test-runner-ipc-");
    Path c2j = directory.resolve("c2j.pipe");
    Path j2c = directory.resolve("j2c.pipe");
    Process process = null;
    WireChannel channel = null;
    try {
      for (Path pipe : new Path[] {c2j, j2c}) {
        if (new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor() != 0) {
          throw new IOException("mkfifo failed for " + pipe);
        }
      }
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
      builder.environment().clear();
      builder.environment().putAll(environment);
      builder.environment().put("BAZEL_PERSISTENT_IPC_C2J", c2j.toString());
      builder.environment().put("BAZEL_PERSISTENT_IPC_J2C", j2c.toString());
      long started = System.nanoTime();
      process = builder.start();
      Thread outDrain = drain(process.getInputStream(), stdout);
      Thread errDrain = drain(process.getErrorStream(), stderr);
      channel =
          FifoConnection.open(
              process,
              c2j,
              j2c,
              Math.max(
                  1, timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
      ReusableJvm jvm =
          new ReusableJvm(
              key, process, directory, c2j, j2c, channel, outDrain, errDrain, store, stdout,
              stderr);
      CompletableFuture<Integer> ready = new CompletableFuture<>();
      jvm.result = ready;
      jvm.reader.start();
      channel.send(SessionStart.INSTANCE);
      long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
      try {
        ready.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
      } catch (ExecutionException | TimeoutException e) {
        jvm.close();
        throw new IOException("Reusable child startup failed", e);
      }
      return jvm;
    } catch (IOException | InterruptedException e) {
      boolean interrupted = false;
      if (process != null) {
        process.destroyForcibly();
        interrupted = waitForExit(process, 1000);
      }
      if (channel != null) {
        closeWire(channel);
      }
      Files.deleteIfExists(c2j);
      Files.deleteIfExists(j2c);
      Files.deleteIfExists(directory);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      throw e;
    }
  }

  boolean compatible(String key, List<JvmProcess.ClasspathEntry> requested) throws IOException {
    if (!startupKey.equals(key) || retiring || !process.isAlive() || !reader.isAlive()) {
      return false;
    }
    for (Map.Entry<String, String> loaded : jars.entrySet()) {
      try {
        if (!loaded.getValue().equals(digest(java.nio.file.Paths.get(loaded.getKey())))) {
          return false;
        }
      } catch (IOException e) {
        return false;
      }
    }
    for (JvmProcess.ClasspathEntry entry : requested) {
      String byPath = jars.get(entry.path);
      String byLabel = labels.get(entry.label);
      if ((byPath != null && !byPath.equals(entry.sha256))
          || (byLabel != null && !byLabel.equals(entry.sha256))) {
        return false;
      }
    }
    return true;
  }

  int execute(
      TestStart request,
      List<JvmProcess.ClasspathEntry> requested,
      PrintStream stdout,
      PrintStream stderr)
      throws IOException, InterruptedException {
    this.stdout = stdout;
    this.stderr = stderr;
    CompletableFuture<Integer> finished = new CompletableFuture<>();
    result = finished;
    if (failure != null || !reader.isAlive()) {
      throw new IOException("Reusable child session is closed");
    }
    for (JvmProcess.ClasspathEntry entry : requested) {
      jars.put(entry.path, entry.sha256);
      labels.put(entry.label, entry.sha256);
    }
    channel.send(request);
    try {
      int exitCode = finished.get();
      if (retiring) {
        close();
      }
      return exitCode;
    } catch (ExecutionException e) {
      throw new IOException("Reusable child test failed", e.getCause());
    }
  }

  private void read() {
    try {
      while (true) {
        Message message = channel.read();
        if (message == null) {
          throw new IOException("Reusable child closed its session");
        }
        if (message instanceof SessionReady) {
          channel.send(WorkerStart.INSTANCE);
          result.complete(0);
        } else if (message instanceof WorkerShutdown) {
          // Child retirement precedes its final TestFinished. Preserve the result
          // and reap it before execute returns to the next serial dispatch.
          retiring = true;
        } else if (message instanceof TestFinished) {
          result.complete(((TestFinished) message).exitCode());
        } else if (message instanceof TestOutput) {
          TestOutput output = (TestOutput) message;
          PrintStream destination = output.stderr ? stderr : stdout;
          destination.write(output.bytes, 0, output.bytes.length);
          destination.flush();
        } else if (message instanceof StoreGetRequest) {
          StoreGetRequest request = (StoreGetRequest) message;
          Optional<StoreValue> value = store.get(request.key());
          channel.send(
              new StoreGetResponse(request.requestId(), value.isPresent(), value.orElse(null)));
        } else if (message instanceof StoreSetRequest) {
          StoreSetRequest request = (StoreSetRequest) message;
          store.set(request.key(), request.value());
          channel.send(new StoreSetAck(request.requestId()));
        } else {
          throw new IOException("Unexpected reusable child message: " + message.getClass());
        }
      }
    } catch (IOException | RuntimeException e) {
      failure = e;
      // Preserve final bootstrap/uncaught-error diagnostics before completing a
      // failed request. The wire can close before the process stderr is drained.
      if (e instanceof IOException && !process.isAlive()) {
        try {
          stdoutDrain.join(250);
          stderrDrain.join(250);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      CompletableFuture<Integer> pending = result;
      if (pending != null) {
        pending.completeExceptionally(e);
      }
    }
  }

  static String digest(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
          digest.update(buffer, 0, count);
        }
      }
      StringBuilder hex = new StringBuilder();
      for (byte value : digest.digest()) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void close() {
    boolean interrupted = Thread.interrupted();
    try {
      channel.send(WorkerShutdown.INSTANCE);
      if (!process.waitFor(1, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (IOException e) {
      process.destroyForcibly();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      interrupted = true;
    } finally {
      process.destroyForcibly();
      closeWire(channel);
      interrupted |= waitForExit(process, 1000);
      try {
        reader.join(1000);
        stdoutDrain.join(1000);
        stderrDrain.join(1000);
        Files.deleteIfExists(c2j);
        Files.deleteIfExists(j2c);
        Files.deleteIfExists(directory);
      } catch (InterruptedException e) {
        interrupted = true;
      } catch (IOException e) {
        stderr.println("Failed to remove reusable JVM IPC: " + e);
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Reaps a killed child without allowing a cancellation interrupt to skip cleanup. */
  static boolean waitForExit(Process process, long timeoutMillis) {
    boolean interrupted = Thread.interrupted();
    long started = System.nanoTime();
    long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (process.isAlive()) {
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) {
        break;
      }
      try {
        process.waitFor(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    return interrupted;
  }

  private static void closeWire(WireChannel channel) {
    Thread close =
        new Thread(
            () -> {
              try {
                channel.close();
              } catch (IOException ignored) {
              }
            },
            "persistent-test-runner-wire-close");
    close.setDaemon(true);
    close.start();
  }

  private static Thread drain(InputStream input, PrintStream destination) {
    Thread thread =
        new Thread(
            () -> {
              try {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                  destination.write(buffer, 0, count);
                }
              } catch (IOException ignored) {
              }
            },
            "reusable-child-output-drain");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }
}
