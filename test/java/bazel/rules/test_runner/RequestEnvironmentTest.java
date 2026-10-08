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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Sends distinct environments through one real coordinator to real child JVMs. */
public final class RequestEnvironmentTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("request environment test ").toAbsolutePath();
    try {
      Path config = root.resolve("config.pb");
      List<String> lines = new ArrayList<>(Arrays.asList(
          "path_mode=execroot", "execroot_relative_config=config.pb",
          "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
          "main_class=" + Child.class.getName(), "driver_main=" + JvmDriver.class.getName()));
      for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
        lines.add("driver_classpath_entry=" + Paths.get(entry).toAbsolutePath());
      }
      Files.write(config, lines, StandardCharsets.UTF_8);
      for (String marker : Arrays.asList("first", "second", "base")) {
        Files.createDirectories(root.resolve(marker));
      }
      FieldDescriptor environmentField = WorkRequest.getDescriptor().findFieldByName("environment");
      if (environmentField != null) {
        exerciseWorker(root, config, environmentField);
      } else {
        System.out.println("SKIP request environment field: stock Bazel protocol");
      }
      exerciseSingleShot(root, config);
    } finally {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static Map<String, String> environment(Path root, String marker) {
    Map<String, String> env = new HashMap<>();
    env.put("TEST_PERSISTENT_MARKER", marker);
    env.put("TESTBRIDGE_TEST_ONLY", "filter-" + marker);
    env.put("TEST_TOTAL_SHARDS", "3");
    env.put("TEST_SHARD_INDEX", marker.equals("first") ? "0" : marker.equals("second") ? "1" : "2");
    env.put("COVERAGE_PERSISTENT_MARKER", marker);
    env.put("TEST_TMPDIR", root.resolve(marker).toString());
    env.put("XML_OUTPUT_FILE", root.resolve(marker + ".xml").toString());
    return env;
  }

  private static ProcessBuilder coordinator(Path root, Path config, boolean worker) {
    List<String> command = new ArrayList<>(Arrays.asList(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dbazel.persistent.startup_timeout_seconds=2", "-classpath",
        System.getProperty("java.class.path"), PersistentTestRunner.class.getName()));
    if (worker) {
      command.add("--persistent_worker");
    } else {
      command.addAll(Arrays.asList("--config", config.toString(), "--", "base", "absent"));
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().putAll(environment(root, "base"));
    builder.environment().put("PERSISTENT_ENV_BASE", "unchanged");
    builder.environment().remove("TEST_PERSISTENT_FIRST_ONLY");
    builder.redirectError(root.resolve(worker ? "worker.stderr" : "single-shot.stderr").toFile());
    return builder;
  }

  private static void exerciseWorker(Path root, Path config, FieldDescriptor environmentField)
      throws Exception {
    Process process = coordinator(root, config, true).start();
    ExecutorService reader = Executors.newSingleThreadExecutor();
    try {
      int id = 1;
      for (String marker : Arrays.asList("first", "second", "base")) {
        Map<String, String> env = marker.equals("base") ? Collections.emptyMap() : environment(root, marker);
        if (marker.equals("first")) {
          env.put("TEST_PERSISTENT_FIRST_ONLY", "first-only");
        }
        WorkRequest.Builder request = WorkRequest.newBuilder().setRequestId(id)
            .addAllArguments(Arrays.asList("--config", config.toString(), "--", marker,
                marker.equals("first") ? "first-only" : "absent"));
        for (Map.Entry<String, String> entry : env.entrySet()) {
          request.addRepeatedField(environmentField,
              DynamicMessage.newBuilder(environmentField.getMessageType())
                  .setField(environmentField.getMessageType().findFieldByName("key"), entry.getKey())
                  .setField(environmentField.getMessageType().findFieldByName("value"), entry.getValue())
                  .build());
        }
        request.build().writeDelimitedTo(process.getOutputStream());
        process.getOutputStream().flush();
        WorkResponse response = reader.submit(() -> WorkResponse.parseDelimitedFrom(process.getInputStream()))
            .get(8, TimeUnit.SECONDS);
        check(response != null, "Coordinator exited before response: " +
            new String(Files.readAllBytes(root.resolve("worker.stderr")), StandardCharsets.UTF_8));
        check(response.getRequestId() == id && response.getExitCode() == 0, response.toString());
        check(response.getOutput().contains("PASS child environment " + marker), response.getOutput());
        check(new String(Files.readAllBytes(root.resolve(marker + ".xml")), StandardCharsets.UTF_8)
            .equals(marker), "Wrong XML output for " + marker);
        id++;
      }
      process.getOutputStream().close();
      check(process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0,
          "Coordinator did not exit cleanly on EOF");
      System.out.println("PASS three environments through the same coordinator; no leaked request values");
    } finally {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
      reader.shutdownNow();
      reader.awaitTermination(3, TimeUnit.SECONDS);
    }
  }

  private static void exerciseSingleShot(Path root, Path config) throws Exception {
    ProcessBuilder builder = coordinator(root, config, false);
    builder.redirectOutput(root.resolve("single-shot.stdout").toFile());
    Process process = builder.start();
    try {
      check(process.waitFor(8, TimeUnit.SECONDS), "Single-shot coordinator hung");
      check(process.exitValue() == 0,
          new String(Files.readAllBytes(root.resolve("single-shot.stderr")), StandardCharsets.UTF_8));
      check(new String(Files.readAllBytes(root.resolve("single-shot.stdout")), StandardCharsets.UTF_8)
          .contains("PASS child environment base"), "Missing single-shot output");
      System.out.println("PASS single-shot inherited environment");
    } finally {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      String marker = args[0];
      check(marker.equals(System.getenv("TEST_PERSISTENT_MARKER")), "Wrong marker");
      check("unchanged".equals(System.getenv("PERSISTENT_ENV_BASE")), "Base environment lost");
      String firstOnly = System.getenv("TEST_PERSISTENT_FIRST_ONLY");
      check(args[1].equals("absent") ? firstOnly == null : args[1].equals(firstOnly),
          "Previous request's environment leaked");
      check(("filter-" + marker).equals(System.getenv("TESTBRIDGE_TEST_ONLY")), "Wrong filter");
      check("3".equals(System.getenv("TEST_TOTAL_SHARDS")), "Wrong total shards");
      check((marker.equals("first") ? "0" : marker.equals("second") ? "1" : "2")
          .equals(System.getenv("TEST_SHARD_INDEX")), "Wrong shard index");
      check(marker.equals(System.getenv("COVERAGE_PERSISTENT_MARKER")), "Wrong coverage value");
      Path tmpdir = Paths.get(System.getenv("TEST_TMPDIR"));
      check(tmpdir.equals(Paths.get(System.getProperty("java.io.tmpdir"))), "Wrong JVM temp directory");
      Path temporary = Files.createTempFile("request-env-", ".tmp");
      check(temporary.getParent().equals(tmpdir), "Temporary file escaped request directory");
      Files.delete(temporary);
      Files.write(Paths.get(System.getenv("XML_OUTPUT_FILE")), marker.getBytes(StandardCharsets.UTF_8));
      System.out.println("PASS child environment " + marker);
    }
  }
}
