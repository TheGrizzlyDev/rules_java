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

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Cancels real uncooperative child JVMs and keeps the coordinator usable. */
public final class CancellationTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("test-cancellation-").toAbsolutePath();
    Path marker = root.resolve("started.pid");
    ProcessBuilder builder =
        new ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-classpath",
            System.getProperty("java.class.path"),
            PersistentTestRunner.class.getName(),
            "--persistent_worker");
    builder.environment().remove("TEST_WARM_MARKER");
    builder.environment().remove("TEST_RUNFILES_EXPECTED");
    builder.redirectError(root.resolve("coordinator.log").toFile());
    Process coordinator = builder.start();
    ExecutorService reader =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "cancellation-response-reader");
              thread.setDaemon(true);
              return thread;
            });
    Set<String> children = new LinkedHashSet<>();
    try {
      Path hanging = config(root, marker, "Hanging", null, null);
      String cancelled = cancel(coordinator, reader, hanging, marker, root, "warm");
      children.add(cancelled);
      // Late and unknown cancellation must never add responses or affect recovery.
      send(coordinator, WorkRequest.newBuilder().setRequestId(99).setCancel(true).build());
      send(coordinator, WorkRequest.newBuilder().setRequestId(0).setCancel(true).build());
      String first =
          run(coordinator, reader, config(root, marker, "First", null, null), root, "first", 1);
      children.add(first);
      check(!first.equals(cancelled), "Cancelled child was reused");
      String second =
          run(coordinator, reader, config(root, marker, "Second", null, null), root, "second", 2);
      check(first.equals(second), "Recovered requests did not reuse the same JVM");
      System.out.println(
          "PASS cancelled warm child " + cancelled + "; recovery reused child " + first);

      Path legacy = config(root, marker, "legacy", LegacyHang.class.getName(), null);
      children.add(cancel(coordinator, reader, legacy, marker, root, "legacy"));
      check(
          first.equals(
              run(
                  coordinator,
                  reader,
                  config(root, marker, "First", null, null),
                  root,
                  "after-legacy",
                  3)),
          "Cancelling the fresh child affected the warm pool");
      Path startup =
          config(
              root, marker, "startup", LegacyHang.class.getName(), BootstrapHang.class.getName());
      children.add(cancel(coordinator, reader, startup, marker, root, "startup"));
      check(
          first.equals(
              run(
                  coordinator,
                  reader,
                  config(root, marker, "Second", null, null),
                  root,
                  "after-startup",
                  4)),
          "Startup cancellation affected the warm pool");
      System.out.println("PASS cancellation during fresh-child execution and FIFO startup");
      coordinator.getOutputStream().close();
      check(
          coordinator.waitFor(10, TimeUnit.SECONDS) && coordinator.exitValue() == 0,
          "Coordinator did not exit cleanly after cancellation");
      check(
          !Files.exists(Paths.get("/proc", first)),
          "Recovered pooled child survived coordinator EOF");
      System.out.println(
          "PASS cancellation responses were empty and unique; pooled child cleaned up at EOF");
    } finally {
      coordinator.destroy();
      if (!coordinator.waitFor(3, TimeUnit.SECONDS)) {
        coordinator.destroyForcibly();
        coordinator.waitFor(3, TimeUnit.SECONDS);
      }
      // Also reap a fixture child if a protocol regression interrupted the test.
      if (Files.isRegularFile(marker)) {
        children.add(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim());
      }
      for (String pid : children) {
        if (pid.matches("[0-9]+") && Files.exists(Paths.get("/proc", pid))) {
          new ProcessBuilder("kill", "-KILL", pid).start().waitFor();
        }
      }
      reader.shutdownNow();
      try (Stream<Path> paths = Files.walk(root)) {
        for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static String cancel(
      Process coordinator, ExecutorService reader, Path config, Path marker, Path root, String name)
      throws Exception {
    Files.deleteIfExists(marker);
    send(coordinator, request(config, root, name));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Files.isRegularFile(marker) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    check(Files.isRegularFile(marker), "Child did not reach " + name + " cancellation checkpoint");
    String pid = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
    check(
        pid.matches("[0-9]+") && Files.exists(Paths.get("/proc", pid)),
        "Invalid child PID: " + pid);
    long started = System.nanoTime();
    send(coordinator, WorkRequest.newBuilder().setRequestId(0).setCancel(true).build());
    send(coordinator, WorkRequest.newBuilder().setRequestId(0).setCancel(true).build());
    WorkResponse response = response(coordinator, reader);
    check(
        response.getRequestId() == 0 && response.getWasCancelled(),
        "Missing cancellation response");
    check(
        response.getExitCode() == 0 && response.getOutput().isEmpty(),
        "Cancelled response retained output");
    check(
        !Files.exists(Paths.get("/proc", pid)),
        "Cancellation acknowledged before the child exited: " + pid);
    System.out.println(
        "CANCEL_CONTEXT phase="
            + name
            + " pid="
            + pid
            + " elapsed_ms="
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    return pid;
  }

  private static String run(
      Process coordinator,
      ExecutorService reader,
      Path config,
      Path root,
      String name,
      int dispatch)
      throws Exception {
    send(coordinator, request(config, root, name));
    WorkResponse response = response(coordinator, reader);
    check(
        response.getRequestId() == 0 && !response.getWasCancelled() && response.getExitCode() == 0,
        response.getOutput());
    Matcher identity =
        Pattern.compile("JVM_REUSE .* pid=(\\d+) token=\\S+ dispatch=(\\d+)")
            .matcher(response.getOutput());
    check(identity.find(), "Missing recovered child identity: " + response.getOutput());
    check(Integer.parseInt(identity.group(2)) == dispatch, "Wrong recovered dispatch count");
    Matcher state = Pattern.compile("WARM_STATE .* count=(\\d+)").matcher(response.getOutput());
    check(
        state.find() && Integer.parseInt(state.group(1)) == dispatch,
        "Warm classloader state was reset after cancellation: " + response.getOutput());
    return identity.group(1);
  }

  private static WorkResponse response(Process coordinator, ExecutorService reader)
      throws Exception {
    WorkResponse response =
        reader
            .submit(() -> WorkResponse.parseDelimitedFrom(coordinator.getInputStream()))
            .get(8, TimeUnit.SECONDS);
    check(response != null, "Coordinator closed its response stream");
    return response;
  }

  private static WorkRequest request(Path config, Path root, String name) {
    WorkRequest.Builder request =
        WorkRequest.newBuilder()
            .setRequestId(0)
            .addAllArguments(Arrays.asList("--config", config.toString(), "--"));
    FieldDescriptor field = WorkRequest.getDescriptor().findFieldByName("environment");
    if (field != null) {
      Map<String, String> environment = new LinkedHashMap<>();
      environment.put("TEST_TARGET", "//cancellation:" + name);
      environment.put("TEST_TMPDIR", root.resolve("tmp-" + name).toString());
      environment.put("XML_OUTPUT_FILE", root.resolve(name + ".xml").toString());
      for (Map.Entry<String, String> entry : environment.entrySet()) {
        request.addRepeatedField(
            field,
            DynamicMessage.newBuilder(field.getMessageType())
                .setField(field.getMessageType().findFieldByName("key"), entry.getKey())
                .setField(field.getMessageType().findFieldByName("value"), entry.getValue())
                .build());
      }
    }
    return request.build();
  }

  private static void send(Process coordinator, WorkRequest request) throws Exception {
    request.writeDelimitedTo(coordinator.getOutputStream());
    coordinator.getOutputStream().flush();
  }

  private static Path config(Path root, Path marker, String name, String main, String driver)
      throws Exception {
    List<String> lines =
        new ArrayList<>(
            Arrays.asList(
                "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
                "main_class="
                    + (main == null ? "com.google.testing.junit.runner.BazelTestRunner" : main),
                "driver_main=" + (driver == null ? JvmDriver.class.getName() : driver),
                "jvm_flag=-Dtest.cancel.started=" + marker));
    if (main == null) {
      lines.add("jvm_flag=-Dbazel.test_suite=" + WarmFixtures.class.getName() + "$" + name);
    }
    Set<String> bootstrap = new LinkedHashSet<>();
    for (String type :
        Arrays.asList(
            JvmDriver.class.getName(),
            "com.google.devtools.build.java.testrunner.wire.Message",
            "com.google.devtools.build.java.testrunner.extension.Extension")) {
      bootstrap.add(
          Paths.get(Class.forName(type).getProtectionDomain().getCodeSource().getLocation().toURI())
              .toString());
    }
    for (String entry : bootstrap) {
      lines.add("driver_classpath_entry=" + entry);
    }
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      String absolute = Paths.get(entry).toAbsolutePath().toString();
      lines.add("classpath_entry_label=" + absolute);
      lines.add("classpath_entry_path=" + absolute);
    }
    Path config = root.resolve(name + ".config");
    Files.write(config, lines, StandardCharsets.UTF_8);
    return config;
  }

  private static void check(boolean value, String message) {
    if (!value) {
      throw new AssertionError(message);
    }
  }

  public static final class LegacyHang {
    public static void main(String[] args) throws Exception {
      hang();
    }
  }

  public static final class BootstrapHang {
    public static void main(String[] args) throws Exception {
      hang();
    }
  }

  private static void hang() throws Exception {
    String pid = "";
    for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
      if (line.startsWith("Pid:")) {
        pid = line.substring(4).trim();
      }
    }
    Path marker = Paths.get(System.getProperty("test.cancel.started"));
    Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
    Files.write(temporary, Arrays.asList(pid), StandardCharsets.UTF_8);
    Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    while (true) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ignored) {
      }
    }
  }
}
