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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Run with bazel coverage: validates actual retained-JVM reports and probe reset. */
public final class CoverageReuseTest {
  public static void main(String[] args) throws Exception {
    Class.forName("org.jacoco.agent.rt.RT");
    Path root = Files.createTempDirectory("coverage-reuse-");
    Constructor<PersistentTestRunner> constructor =
        PersistentTestRunner.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    PersistentTestRunner coordinator = constructor.newInstance();
    Method close = PersistentTestRunner.class.getDeclaredMethod("closeChildren");
    close.setAccessible(true);
    String identity = null;
    String stateToken = null;
    try {
      int dispatch = 0;
      for (String suite : Arrays.asList("First", "Second", "First")) {
        Path directory = root.resolve("request-" + (++dispatch));
        Files.createDirectories(directory);
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("COVERAGE_DIR", directory.toString());
        environment.put("JAVA_COVERAGE_FILE", directory.resolve("jvcov").toString());
        environment.put("TEST_TARGET", "//coverage:" + suite);
        environment.put("TEST_TMPDIR", directory.resolve("tmp").toString());
        environment.put("XML_OUTPUT_FILE", directory.resolve("test.xml").toString());
        Path config = config(directory, suite);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, "UTF-8");
        int result =
            coordinator.handleRequest(
                Arrays.asList("--config", config.toString(), "--"), environment, output, output);
        String log = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        System.out.print(log);
        check(result == 0, "Covered suite failed: " + log);
        Matcher child =
            Pattern.compile("JVM_REUSE .* pid=(\\d+) token=(\\S+) dispatch=(\\d+)").matcher(log);
        check(
            child.find() && Integer.parseInt(child.group(3)) == dispatch,
            "Missing dispatch identity");
        String current = child.group(1) + ":" + child.group(2);
        if (identity == null) {
          identity = current;
        }
        check(identity.equals(current), "Coverage started a fresh JVM");
        Matcher state = Pattern.compile("COVERAGE_WARM token=(\\S+) count=(\\d+)").matcher(log);
        check(
            state.find() && Integer.parseInt(state.group(2)) == dispatch,
            "Warm application state reset");
        if (stateToken == null) {
          stateToken = state.group(1);
        }
        check(stateToken.equals(state.group(1)), "Warm classloader changed");
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
          files.filter(path -> path.toString().endsWith(".dat")).forEach(reports::add);
        }
        check(reports.size() == 1, "Expected exactly one per-request report");
        String report = new String(Files.readAllBytes(reports.get(0)), StandardCharsets.UTF_8);
        String covered = suite.equals("First") ? "firstBranch" : "secondBranch";
        String uncovered = suite.equals("First") ? "secondBranch" : "firstBranch";
        check(Pattern.compile("(?m)^FNDA:1,.*" + covered + ".*$").matcher(report).find(), report);
        check(
            Pattern.compile("(?m)^FNDA:0,.*" + uncovered + ".*$").matcher(report).find(),
            "Earlier dispatch coverage leaked: " + report);
        System.out.println("PASS coverage dispatch " + dispatch + " covered " + covered + " only");
      }
    } finally {
      close.invoke(coordinator);
      if (identity != null) {
        check(
            !Files.exists(Paths.get("/proc", identity.split(":")[0])),
            "Covered child survived cleanup");
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static Path config(Path directory, String suite) throws Exception {
    List<String> lines =
        new ArrayList<>(
            Arrays.asList(
                "javabin=" + Paths.get(System.getProperty("java.home"), "bin", "java"),
                "main_class=com.google.testing.coverage.JacocoCoverageRunner",
                "coverage_main_class=com.google.testing.junit.runner.BazelTestRunner",
                "driver_main=" + JvmDriver.class.getName(),
                "jvm_flag=-Dbazel.test_suite=com.google.devtools.build.java.testrunner.coverage.CoverageFixtures$"
                    + suite));
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
    Path config = directory.resolve("test.config");
    Files.write(config, lines, StandardCharsets.UTF_8);
    return config;
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
