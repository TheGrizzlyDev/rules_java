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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Verifies arguments through the coordinator protocol and real child JVMs. */
public final class RequestArgumentsTest {
  private static final List<String> ARGUMENTS = Arrays.asList(
      "first", "", "space value", "'quoted'", "double\"quote", "back\\slash",
      "@missing-file", "@@literal", "@repo//label", "--config=program-option",
      "--persistent_worker", "--", "last");

  public static void main(String[] args) throws Exception {
    if (args.length != 0) {
      throw new AssertionError("Unexpected test arguments: " + Arrays.toString(args));
    }
    Path root = Files.createTempDirectory("request arguments ").toAbsolutePath();
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
      exerciseWorker(config);
      List<String> invocation = new ArrayList<>(Arrays.asList("--config", config.toString(), "--"));
      invocation.add("fallback");
      invocation.addAll(ARGUMENTS);
      exerciseSingleShot(root, invocation);
      Path params = root.resolve("arguments.params");
      Files.write(params, invocation, StandardCharsets.UTF_8);
      exerciseSingleShot(root, Arrays.asList("@" + params));
      System.out.println("PASS arguments through repeated requests, direct and paramfile fallback");
    } finally {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static ProcessBuilder coordinator(List<String> arguments) {
    List<String> command = new ArrayList<>(Arrays.asList(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dbazel.persistent.startup_timeout_seconds=2", "-classpath",
        System.getProperty("java.class.path"), PersistentTestRunner.class.getName()));
    command.addAll(arguments);
    return new ProcessBuilder(command);
  }

  private static void exerciseWorker(Path config) throws Exception {
    Process process = coordinator(Arrays.asList("--persistent_worker")).start();
    ExecutorService reader = Executors.newSingleThreadExecutor();
    try {
      for (int id = 1; id <= 2; id++) {
        List<String> arguments = new ArrayList<>(Arrays.asList("--config", config.toString(), "--"));
        // Wrapper flags belong to this request, not the coordinator JVM.
        arguments.add("--jvm_flag=-Dpersistent.argument.marker=present");
        arguments.add("worker");
        arguments.addAll(ARGUMENTS);
        WorkRequest.newBuilder().setRequestId(id).addAllArguments(arguments).build()
            .writeDelimitedTo(process.getOutputStream());
        process.getOutputStream().flush();
        WorkResponse response = reader.submit(() -> WorkResponse.parseDelimitedFrom(process.getInputStream()))
            .get(8, TimeUnit.SECONDS);
        check(response != null && response.getRequestId() == id && response.getExitCode() == 0,
            "Failed request: " + response);
        check(response.getOutput().contains("PASS child arguments"), response.getOutput());
      }
      process.getOutputStream().close();
      check(process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0,
          "Coordinator did not exit on EOF");
    } finally {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
      reader.shutdownNow();
    }
  }

  private static void exerciseSingleShot(Path root, List<String> arguments) throws Exception {
    Path log = root.resolve("single-shot.log");
    Process process = coordinator(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      check(process.waitFor(8, TimeUnit.SECONDS), "Single-shot coordinator timed out");
      String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
      check(process.exitValue() == 0 && output.contains("PASS child arguments"), output);
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
    public static void main(String[] args) {
      check(args.length == ARGUMENTS.size() + 1
          && Arrays.asList(args).subList(1, args.length).equals(ARGUMENTS),
          "Changed arguments: " + Arrays.toString(args));
      String marker = System.getProperty("persistent.argument.marker");
      check(args[0].equals("worker") ? "present".equals(marker) : marker == null,
          "Changed wrapper JVM flag: " + marker);
      System.out.println("PASS child arguments");
    }
  }
}
