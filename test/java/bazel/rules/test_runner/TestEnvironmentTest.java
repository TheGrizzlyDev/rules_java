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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Launches a real child with relative paths and missing per-test directories. */
public final class TestEnvironmentTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("test environment ").toAbsolutePath();
    try {
      Path config = root.resolve("config.pb");
      List<String> lines =
          new ArrayList<>(
              Arrays.asList(
                  "path_mode=execroot",
                  "execroot_relative_config=config.pb",
                  "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
                  "main_class=" + Child.class.getName(),
                  "driver_main=" + JvmDriver.class.getName()));
      for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
        lines.add("driver_classpath_entry=" + Paths.get(entry).toAbsolutePath());
      }
      Files.write(config, lines, StandardCharsets.UTF_8);
      Map<String, String> env = new HashMap<>(System.getenv());
      env.put("BAZEL_TEST", "incorrect");
      env.put("TEST_TMPDIR", "work/tmp");
      env.put("XML_OUTPUT_FILE", "outputs/results.xml");
      env.put("TEST_SHARD_STATUS_FILE", "sharding/status");
      env.put("TEST_UNDECLARED_OUTPUTS_DIR", "outputs/undeclared");
      env.put("TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR", "outputs/annotations");
      env.put("TEST_SRCDIR", "runfiles");
      env.put("JAVA_RUNFILES", "runfiles");
      env.put("RUNFILES_DIR", "runfiles");
      env.put("RUNFILES_MANIFEST_FILE", "runfiles/MANIFEST");
      env.put("TEST_TOTAL_SHARDS", "2");
      env.put("TEST_SHARD_INDEX", "1");
      env.put("TESTBRIDGE_TEST_ONLY", "filter with spaces");
      Map<String, String> original = new HashMap<>(env);
      Constructor<PersistentTestRunner> constructor =
          PersistentTestRunner.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
        int result =
            constructor
                .newInstance()
                .handleRequest(
                    Arrays.asList("--config", config.toString(), "--", root.toString()),
                    env,
                    capture,
                    capture);
        check(result == 0, output.toString("UTF-8"));
      }
      check(env.equals(original), "Setup mutated the request environment");
      check(output.toString("UTF-8").contains("PASS child setup"), output.toString("UTF-8"));
      check(Files.exists(root.resolve("outputs/results.xml")), "Missing XML output");
      check(Files.exists(root.resolve("sharding/status")), "Missing shard status");
      check(
          Files.exists(root.resolve("outputs/undeclared/result.txt")), "Missing undeclared output");
      check(Files.exists(root.resolve("outputs/annotations/result.txt")), "Missing annotation");
      check(
          !TestEnvironment.prepare(new HashMap<>(), root).containsKey("TEST_TMPDIR"),
          "An absent temp directory became an execution-root path");
      Map<String, String> context = new HashMap<>();
      context.put("TEST_SRCDIR", "runfiles");
      context.put("TEST_WORKSPACE", "workspace");
      Path workspace = root.resolve("runfiles/workspace");
      Files.createDirectories(workspace);
      check(
          workspace.equals(TestEnvironment.workingDirectory(context, root)),
          "Directory tests did not enter their workspace");
      context.put("RUNTEST_PRESERVE_CWD", "1");
      check(root.equals(TestEnvironment.workingDirectory(context, root)), "Preserved cwd changed");
      context.remove("RUNTEST_PRESERVE_CWD");
      context.put("COVERAGE_DIR", "coverage");
      check(root.equals(TestEnvironment.workingDirectory(context, root)), "Coverage cwd changed");
      context.put("COVERAGE_MANIFEST", "coverage.manifest");
      context.put("COVERAGE_OUTPUT_FILE", "coverage.dat");
      Map<String, String> coverage = TestEnvironment.prepare(context, root);
      for (String name :
          Arrays.asList("COVERAGE_DIR", "COVERAGE_MANIFEST", "COVERAGE_OUTPUT_FILE")) {
        check(root.resolve(context.get(name)).equals(Paths.get(coverage.get(name))), name);
      }
      check(Files.isDirectory(root.resolve("coverage")), "Coverage directory was not created");
      check(
          root.resolve("coverage/jvcov").equals(Paths.get(coverage.get("JAVA_COVERAGE_FILE"))),
          "Missing coverage destination");
      context.put("JAVA_COVERAGE_FILE", "explicit/jvcov");
      check(
          root.resolve("explicit/jvcov")
              .equals(Paths.get(TestEnvironment.prepare(context, root).get("JAVA_COVERAGE_FILE"))),
          "Explicit coverage destination changed");
      context.remove("COVERAGE_DIR");
      context.put("TEST_SRCDIR", "missing.runfiles");
      try {
        TestEnvironment.workingDirectory(context, root);
        throw new AssertionError("Missing directory tree was silently accepted");
      } catch (java.io.IOException expected) {
        // Missing directory-mode runfiles are a request failure.
      }
      context.put("RUNFILES_MANIFEST_ONLY", "1");
      check(
          root.equals(TestEnvironment.workingDirectory(context, root)),
          "Manifest-only execution did not use the execution root");
      System.out.println(
          "PASS relative paths, directory setup, request isolation, and cwd selection");
    } finally {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      Path root = Paths.get(args[0]);
      check("1".equals(System.getenv("BAZEL_TEST")), "Missing Bazel test marker");
      check("filter with spaces".equals(System.getenv("TESTBRIDGE_TEST_ONLY")), "Filter changed");
      check("2".equals(System.getenv("GTEST_TOTAL_SHARDS")), "Wrong total shards");
      check("1".equals(System.getenv("GTEST_SHARD_INDEX")), "Wrong shard index");
      for (String name : Arrays.asList("TEST_SRCDIR", "JAVA_RUNFILES", "RUNFILES_DIR")) {
        check(root.resolve("runfiles").equals(Paths.get(System.getenv(name))), "Wrong " + name);
      }
      check(
          root.resolve("runfiles/MANIFEST")
              .equals(Paths.get(System.getenv("RUNFILES_MANIFEST_FILE"))),
          "Wrong runfiles manifest path");
      Path tmpdir = root.resolve("work/tmp");
      check(tmpdir.equals(Paths.get(System.getenv("TEST_TMPDIR"))), "Wrong TEST_TMPDIR");
      check(tmpdir.equals(Paths.get(System.getenv("GTEST_TMP_DIR"))), "Wrong GTEST_TMP_DIR");
      check(
          tmpdir.equals(Paths.get(System.getProperty("java.io.tmpdir"))),
          "Wrong JVM temp directory");
      Path temporary = Files.createTempFile("setup-", ".tmp");
      check(temporary.getParent().equals(tmpdir), "Temporary file escaped test directory");
      Files.delete(temporary);
      for (String name : Arrays.asList("XML_OUTPUT_FILE", "TEST_SHARD_STATUS_FILE")) {
        Files.write(Paths.get(System.getenv(name)), new byte[0]);
      }
      for (String name :
          Arrays.asList("TEST_UNDECLARED_OUTPUTS_DIR", "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR")) {
        Files.write(Paths.get(System.getenv(name)).resolve("result.txt"), new byte[0]);
      }
      System.out.println("PASS child setup");
    }
  }
}
