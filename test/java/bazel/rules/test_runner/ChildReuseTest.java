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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Pass, fail, pass through real coordinator requests into the same child JVM. */
public final class ChildReuseTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("child-reuse-test-").toAbsolutePath();
    List<String> command =
        Arrays.asList(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-classpath",
            System.getProperty("java.class.path"),
            PersistentTestRunner.class.getName(),
            "--persistent_worker");
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().remove("TEST_WARM_MARKER");
    builder.redirectError(root.resolve("coordinator.log").toFile());
    Process process = builder.start();
    ExecutorService reader = Executors.newSingleThreadExecutor();
    Path fixtureSource =
        Paths.get(
            Class.forName("com.google.devtools.build.java.testrunner.WarmFixtures")
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    Path fixtureCopy = root.resolve("warm-fixtures.jar");
    Files.copy(fixtureSource, fixtureCopy);
    String child = null;
    String startup = null;
    String warm = null;
    FieldDescriptor envField = WorkRequest.getDescriptor().findFieldByName("environment");
    try {
      String[] suites = {"First", "Failure", "Second", "First"};
      String[] markers = {"first", "failure", "second", "first"};
      for (int i = 0; i < suites.length; i++) {
        if (i == 3) {
          changeJarDigest(fixtureCopy);
        }
        Path config = root.resolve("request-" + i + ".config");
        List<String> lines =
            new ArrayList<>(
                Arrays.asList(
                    "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
                    "main_class=com.google.testing.junit.runner.BazelTestRunner",
                    "driver_main=" + JvmDriver.class.getName(),
                    "jvm_flag=-Dbazel.test_suite=com.google.devtools.build.java.testrunner.WarmFixtures$"
                        + suites[i]));
        Set<String> bootstrap = new LinkedHashSet<>();
        for (String name :
            Arrays.asList(
                JvmDriver.class.getName(),
                "com.google.devtools.build.java.testrunner.wire.Message",
                "com.google.devtools.build.java.testrunner.extension.Extension")) {
          bootstrap.add(
              Paths.get(
                      Class.forName(name)
                          .getProtectionDomain()
                          .getCodeSource()
                          .getLocation()
                          .toURI())
                  .toString());
        }
        for (String path : bootstrap) {
          lines.add("driver_classpath_entry=" + path);
        }
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
          String path = Paths.get(entry).toAbsolutePath().toString();
          lines.add("classpath_entry_label=" + path);
          lines.add(
              "classpath_entry_path="
                  + (Paths.get(path).equals(fixtureSource) ? fixtureCopy : path));
        }
        Files.write(config, lines, StandardCharsets.UTF_8);
        WorkRequest.Builder request =
            WorkRequest.newBuilder()
                .setRequestId(i + 1)
                .addAllArguments(Arrays.asList("--config", config.toString(), "--"));
        Path xml = root.resolve("result-" + i + ".xml");
        if (envField != null) {
          Map<String, String> environment = new LinkedHashMap<>();
          environment.put("TEST_WARM_MARKER", markers[i]);
          environment.put("TEST_TARGET", "//reuse:" + suites[i]);
          environment.put("XML_OUTPUT_FILE", xml.toString());
          environment.put("TEST_TMPDIR", root.resolve("tmp-" + i).toString());
          for (Map.Entry<String, String> entry : environment.entrySet()) {
            request.addRepeatedField(
                envField,
                DynamicMessage.newBuilder(envField.getMessageType())
                    .setField(envField.getMessageType().findFieldByName("key"), entry.getKey())
                    .setField(envField.getMessageType().findFieldByName("value"), entry.getValue())
                    .build());
          }
        } else {
          // Stock proto cannot vary the environment; still prove returning dispatch.

        }
        request.build().writeDelimitedTo(process.getOutputStream());
        process.getOutputStream().flush();
        WorkResponse response =
            reader
                .submit(() -> WorkResponse.parseDelimitedFrom(process.getInputStream()))
                .get(10, TimeUnit.SECONDS);
        check(response != null && response.getRequestId() == i + 1, "Missing response");
        check(
            response.getExitCode() == (i == 1 ? 1 : 0),
            response.getOutput()
                + "\nCoordinator stderr: "
                + new String(
                    Files.readAllBytes(root.resolve("coordinator.log")), StandardCharsets.UTF_8));
        Matcher identity =
            Pattern.compile("JVM_REUSE .* pid=(\\d+) token=(\\S+) dispatch=(\\d+)")
                .matcher(response.getOutput());
        check(identity.find(), response.getOutput());
        if (i == 0) {
          child = identity.group(1);
          startup = identity.group(2);
        }
        if (i < 3) {
          check(
              child.equals(identity.group(1))
                  && startup.equals(identity.group(2))
                  && Integer.parseInt(identity.group(3)) == i + 1,
              "Child was replaced: " + response.getOutput());
        } else {
          check(
              !child.equals(identity.group(1))
                  && !startup.equals(identity.group(2))
                  && identity.group(3).equals("1"),
              "Changed jar reused the stale child: " + response.getOutput());
          check(!Files.exists(Paths.get("/proc", child)), "Invalidated child leaked");
          System.out.println(
              "PASS First -> Failure -> Second shared child "
                  + child
                  + ", startup "
                  + startup
                  + ", warm classloader state "
                  + warm);
          child = identity.group(1);
        }
        Matcher state =
            Pattern.compile("WARM_STATE marker=" + markers[i] + " token=(\\S+) count=(\\d+)")
                .matcher(response.getOutput());
        check(state.find(), response.getOutput());
        if (i == 0) {
          warm = state.group(1);
        }
        if (i < 3) {
          check(
              warm.equals(state.group(1)) && Integer.parseInt(state.group(2)) == i + 1,
              "Classloader/static state was replaced: " + response.getOutput());
        } else {
          check(
              !warm.equals(state.group(1)) && state.group(2).equals("1"),
              "Changed jar retained the stale classloader: " + response.getOutput());
        }
        if (envField != null) {
          check(
              Files.exists(xml)
                  && new String(Files.readAllBytes(xml), StandardCharsets.UTF_8)
                      .contains("WarmFixtures$" + suites[i]),
              "Wrong per-request XML: " + xml);
        }
      }
      process.getOutputStream().close();
      check(
          process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
          "Coordinator did not shut down");
      check(!Files.exists(Paths.get("/proc", child)), "Pooled child survived coordinator EOF");
      System.out.println(
          "PASS changed jar digest started a fresh child "
              + child
              + "; coordinator EOF cleaned up pooled children");
    } finally {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
      reader.shutdownNow();
      try (Stream<Path> files = Files.walk(root)) {
        for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void changeJarDigest(Path jar) throws Exception {
    Path replacement = jar.resolveSibling("replacement.jar");
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(jar));
        ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(replacement))) {
      ZipEntry entry;
      byte[] bytes = new byte[4096];
      while ((entry = input.getNextEntry()) != null) {
        output.putNextEntry(new ZipEntry(entry.getName()));
        int count;
        while ((count = input.read(bytes)) != -1) {
          output.write(bytes, 0, count);
        }
        output.closeEntry();
      }
      output.putNextEntry(new ZipEntry("changed-digest-proof"));
      output.write("new jar revision".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
    Files.move(replacement, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
