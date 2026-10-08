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
import java.util.List;
import java.util.stream.Stream;

/** Launches a real driver with paths rooted outside the coordinator's cwd/runfiles. */
public final class ConfigPathsTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("config-paths-test-").toAbsolutePath();
    try {
      Path file = root.resolve("bazel-out/config/test.pb");
      Files.createDirectories(file.getParent());
      Files.createDirectories(root.resolve("external/jdk/bin"));
      Files.createSymbolicLink(root.resolve("external/jdk/bin/java"),
          Paths.get(System.getProperty("java.home"), "bin", "java"));
      Files.write(root.resolve("extensions.txt"), new byte[0]);
      List<String> lines = new ArrayList<>(Arrays.asList(
          "path_mode=execroot", "execroot_relative_config=bazel-out/config/test.pb",
          "javabin=external/jdk/bin/java", "main_class=" + Child.class.getName(),
          "driver_main=" + JvmDriver.class.getName(), "extensions_file=extensions.txt"));
      int i = 0;
      for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
        String name = "jar-" + i++ + ".jar";
        Files.createSymbolicLink(root.resolve(name), Paths.get(entry).toAbsolutePath());
        if (i == 1) {
          lines.add("classpath_entry_label=//test:synthetic");
          lines.add("classpath_entry_path=" + name);
        } else {
          lines.add("driver_classpath_entry=" + name);
        }
      }
      Files.write(file, lines, StandardCharsets.UTF_8);
      Config config = Config.parse(file);
      check(config.execRoot.equals(root), "Wrong execution root: " + config.execRoot);
      Constructor<PersistentTestRunner> ctor = PersistentTestRunner.class.getDeclaredConstructor();
      ctor.setAccessible(true);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
        int result = ctor.newInstance().handleRequest(
            Arrays.asList("--config", file.toString(), "--", "argument with spaces"),
            capture, capture);
        check(result == 0, "Child failed: " + output.toString("UTF-8"));
      }
      check(output.toString("UTF-8").contains("PASS execution paths"),
          "Test main did not run: " + output.toString("UTF-8"));

      Files.write(file, Arrays.asList("javabin=java", "main_class=Unused"), StandardCharsets.UTF_8);
      check(!Config.parse(file).execPaths, "Legacy configs must keep runfiles semantics");
      Files.write(file, Arrays.asList("javabin=java", "main_class=Unused", "path_mode=invalid"),
          StandardCharsets.UTF_8);
      try {
        Config.parse(file);
        throw new AssertionError("Invalid path mode accepted");
      } catch (IllegalArgumentException expected) {
        check(expected.getMessage().contains("path_mode"), expected.toString());
      }
      System.out.println("PASS config path resolution and legacy compatibility");
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
    public static void main(String[] args) {
      check(Arrays.equals(args, new String[] {"argument with spaces"}),
          "Arguments were not preserved: " + Arrays.toString(args));
      System.out.println("PASS execution paths");
    }
  }
}
