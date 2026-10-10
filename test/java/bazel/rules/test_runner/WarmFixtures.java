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

import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Different suites deliberately share classloader and application state. */
public final class WarmFixtures {
  private static final String token = UUID.randomUUID().toString();
  private static int executions;
  private static final List<Runfiles> retainedRunfiles = new ArrayList<>();
  private static final List<String> retainedContents = new ArrayList<>();
  private static final List<String> retainedPaths = new ArrayList<>();
  private static final List<String> retainedTempDirs = new ArrayList<>();

  private static void exercise(String marker) throws Exception {
    String supplied = System.getenv("TEST_WARM_MARKER");
    if (supplied != null && !marker.equals(supplied)) {
      throw new AssertionError("Previous request environment leaked: " + supplied);
    }
    if (++executions > 1 && !"retained".equals(System.getProperty("warm.proof.state"))) {
      throw new AssertionError("Warm system property was reset");
    }
    System.setProperty("warm.proof.state", "retained");
    System.out.println("WARM_STATE marker=" + marker + " token=" + token + " count=" + executions);
    // Charset's SPI uses the system loader, rather than the thread context loader.
    // This provider exists only in a test jar, outside the child's bootstrap classpath.
    java.nio.charset.Charset charset = java.nio.charset.Charset.forName("X-WARM-TEST-CHARSET");
    if (charset.getClass().getClassLoader() != WarmFixtures.class.getClassLoader()
        || ClassLoader.getSystemClassLoader().loadClass(WarmFixtures.class.getName())
            != WarmFixtures.class) {
      throw new AssertionError("System services cannot see the retained test classloader");
    }
    if (!Arrays.equals("café".getBytes(charset), "café".getBytes(StandardCharsets.UTF_8))) {
      throw new AssertionError("Service-provided charset encoded incorrectly");
    }
    // Default JavaCompiler discovery uses this property, not just loader URLs.
    java.nio.file.Path fixtureJar =
        Paths.get(WarmFixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    boolean compilerCanDiscoverFixture = false;
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      java.nio.file.Path candidate = Paths.get(entry);
      if (Files.exists(candidate) && Files.isSameFile(candidate, fixtureJar)) {
        compilerCanDiscoverFixture = true;
      }
    }
    if (!compilerCanDiscoverFixture) {
      throw new AssertionError("Current test jar missing from compiler classpath");
    }
    if (Boolean.getBoolean("bazel.persistent.reusable_child")) {
      // An agent appending a current validated test jar must leave this JVM reusable.
      ClassLoader loader = ClassLoader.getSystemClassLoader();
      loader
          .getClass()
          .getMethod("appendToClassPathForInstrumentation", String.class)
          .invoke(
              loader,
              Paths.get(
                      WarmFixtures.class
                          .getProtectionDomain()
                          .getCodeSource()
                          .getLocation()
                          .toURI())
                  .toString());
    }
    checkProcessContext();
    String expected = System.getenv("TEST_RUNFILES_EXPECTED");
    if (expected != null) {
      if (System.getenv("TEST_RUNFILES_SOURCE_REPO") == null) {
        String binary = System.getenv("TEST_BINARY");
        String targetName = System.getenv("TEST_TARGET").split(":")[1];
        if (binary == null || !binary.endsWith("/" + targetName)) {
          throw new AssertionError("TEST_BINARY lost the original test: " + binary);
        }
        String temp = System.getenv("TEST_TMPDIR");
        if (retainedTempDirs.contains(temp)) {
          throw new AssertionError("Different targets shared a temporary directory: " + temp);
        }
        retainedTempDirs.add(temp);
      }
      Runfiles.Preloaded preloaded = Runfiles.preload();
      if (preloaded != Runfiles.preload()) {
        throw new AssertionError("Runfiles cache was not reused within the request");
      }
      String path = System.getenv("TEST_RUNFILES_DATA");
      Runfiles runfiles =
          System.getenv("TEST_RUNFILES_SOURCE_REPO") == null
              ? preloaded.unmapped()
              : preloaded.withSourceRepository(System.getenv("TEST_RUNFILES_SOURCE_REPO"));
      checkRunfile(runfiles, path, expected);
      String subprocessClasspath =
          String.join(
              File.pathSeparator,
              Arrays.asList(
                  Paths.get(
                          WarmFixtures.class
                              .getProtectionDomain()
                              .getCodeSource()
                              .getLocation()
                              .toURI())
                      .toString(),
                  Paths.get(
                          Runfiles.class
                              .getProtectionDomain()
                              .getCodeSource()
                              .getLocation()
                              .toURI())
                      .toString()));
      ProcessBuilder subprocess =
          new ProcessBuilder(
              Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
              "-classpath",
              subprocessClasspath,
              RunfilesProbe.class.getName(),
              path,
              expected,
              System.getenv("TEST_RUNFILES_SOURCE_REPO") == null
                  ? ""
                  : System.getenv("TEST_RUNFILES_SOURCE_REPO"));
      subprocess.environment().clear();
      subprocess.environment().putAll(runfiles.getEnvVars());
      Process probe = subprocess.inheritIO().start();
      try {
        if (!probe.waitFor(5, TimeUnit.SECONDS) || probe.exitValue() != 0) {
          throw new AssertionError("Subprocess received the wrong runfiles context");
        }
      } finally {
        probe.destroyForcibly();
      }
      // Previously retained objects must still resolve their original target's
      // files after later requests change directory, manifest, and repo mapping.
      for (int i = 0; i < retainedRunfiles.size(); i++) {
        checkRunfile(retainedRunfiles.get(i), retainedPaths.get(i), retainedContents.get(i));
      }
      retainedRunfiles.add(runfiles);
      retainedPaths.add(path);
      retainedContents.add(expected);
      System.out.println(
          "RUNFILES_CONTEXT expected="
              + expected
              + " retained="
              + retainedRunfiles.size()
              + " directory="
              + System.getenv("RUNFILES_DIR")
              + " manifest="
              + System.getenv("RUNFILES_MANIFEST_FILE"));
    }
  }

  private static void checkProcessContext() throws Exception {
    String expected = System.getenv("TEST_CONTEXT_CWD");
    if (expected == null) {
      expected = System.getProperty("user.dir");
      if (System.getProperty("bazel.persistent.reusable_child") != null
          && System.getenv("TEST_SRCDIR") != null) {
        java.nio.file.Path tree =
            Paths.get(System.getenv("TEST_SRCDIR"))
                .resolve(
                    System.getenv("TEST_WORKSPACE") == null ? "" : System.getenv("TEST_WORKSPACE"));
        if (Files.isDirectory(tree)) {
          expected = tree.toString();
        }
      }
    }
    java.nio.file.Path cwd = Paths.get(expected).toRealPath();
    if (!cwd.equals(Paths.get(System.getProperty("user.dir")).toRealPath())
        || !cwd.equals(new File(".").getCanonicalFile().toPath())
        || !cwd.equals(Paths.get("").toAbsolutePath().toRealPath())
        || !cwd.equals(Paths.get(System.getenv("PWD")).toRealPath())
        || !cwd.equals(Paths.get("/proc/self/cwd").toRealPath())) {
      throw new AssertionError("Java and native working directories differ from " + cwd);
    }
    String name = "context-" + UUID.randomUUID() + ".txt";
    java.nio.file.Path absolute = cwd.resolve(name);
    try {
      try (FileOutputStream output = new FileOutputStream(name)) {
        output.write("relative".getBytes(StandardCharsets.UTF_8));
      }
      if (!Files.exists(absolute)
          || !"relative"
              .equals(new String(Files.readAllBytes(Paths.get(name)), StandardCharsets.UTF_8))
          || !absolute.equals(new File(name).getAbsoluteFile().toPath())) {
        throw new AssertionError("Relative I/O escaped the current working directory");
      }
    } finally {
      Files.deleteIfExists(absolute);
    }
    for (boolean explicit : new boolean[] {false, true}) {
      ProcessBuilder builder =
          new ProcessBuilder(
              "/bin/sh",
              "-c",
              "printf '%s\\n"
                  + "' \"$TEST_WARM_MARKER\" \"${TEST_CONTEXT_VALUE-unset}\""
                  + " \"${TEST_CONTEXT_OPTIONAL-unset}\"; pwd -P");
      if (explicit) {
        // Materializing the environment exercises Java's cached native map too.
        builder.environment();
      }
      Process probe = builder.start();
      try {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = probe.getInputStream().read(buffer)) != -1) {
          output.write(buffer, 0, count);
        }
        String wanted =
            (System.getenv("TEST_WARM_MARKER") == null ? "" : System.getenv("TEST_WARM_MARKER"))
                + "\n"
                + (System.getenv("TEST_CONTEXT_VALUE") == null
                    ? "unset"
                    : System.getenv("TEST_CONTEXT_VALUE"))
                + "\n"
                + (System.getenv("TEST_CONTEXT_OPTIONAL") == null
                    ? "unset"
                    : System.getenv("TEST_CONTEXT_OPTIONAL"))
                + "\n"
                + cwd
                + "\n";
        if (!probe.waitFor(5, TimeUnit.SECONDS)
            || probe.exitValue() != 0
            || !wanted.equals(output.toString("UTF-8"))) {
          throw new AssertionError("Wrong inherited process context: " + output.toString("UTF-8"));
        }
      } finally {
        probe.destroyForcibly();
      }
    }
    System.out.println(
        "PROCESS_CONTEXT cwd=" + cwd + " marker=" + System.getenv("TEST_WARM_MARKER"));
  }

  private static void checkRunfile(Runfiles runfiles, String path, String expected)
      throws Exception {
    String resolved = runfiles.rlocation(path);
    if (resolved == null
        || !expected.equals(
            new String(Files.readAllBytes(Paths.get(resolved)), StandardCharsets.UTF_8).trim())) {
      throw new AssertionError("Wrong runfiles context for " + path + ": " + resolved);
    }
  }

  public static final class RunfilesProbe {
    public static void main(String[] args) throws Exception {
      Runfiles.Preloaded preloaded = Runfiles.preload();
      checkRunfile(
          args[2].isEmpty() ? preloaded.unmapped() : preloaded.withSourceRepository(args[2]),
          args[0],
          args[1]);
    }
  }

  @RunWith(JUnit4.class)
  public static final class First {
    @Test
    public void firstTest() throws Exception {
      exercise("first");
    }
  }

  @RunWith(JUnit4.class)
  public static final class Second {
    @Test
    public void secondTest() throws Exception {
      exercise("second");
    }
  }

  @RunWith(JUnit4.class)
  public static final class UnknownInstrumentation {
    @Test
    public void retiresUnvalidatedAgentJar() throws Exception {
      exercise("unknown");
      java.nio.file.Path jar = Files.createTempFile("unknown-agent-", ".jar");
      try (java.util.jar.JarOutputStream output =
          new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
        // A dynamically generated agent jar is absent from the validated inputs.
      }
      try {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        loader
            .getClass()
            .getMethod("appendToClassPathForInstrumentation", String.class)
            .invoke(loader, jar.toString());
      } finally {
        Files.deleteIfExists(jar);
      }
      throw new AssertionError("Deliberate failure after appending an unvalidated agent jar");
    }
  }

  @RunWith(JUnit4.class)
  public static final class Hanging {
    @Test
    public void waitsForCancellation() throws Exception {
      String pid = "";
      for (String line :
          Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
        if (line.startsWith("Pid:")) {
          pid = line.substring(4).trim();
        }
      }
      java.nio.file.Path marker = Paths.get(System.getProperty("test.cancel.started"));
      java.nio.file.Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
      Files.write(temporary, Arrays.asList(pid), StandardCharsets.UTF_8);
      Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      while (true) {
        try {
          Thread.sleep(10000);
        } catch (InterruptedException ignored) {
          // Deliberately uncooperative: cancellation must retire the JVM.
        }
      }
    }
  }

  @RunWith(JUnit4.class)
  public static final class Failure {
    @Test
    public void failingTest() throws Exception {
      exercise("failure");
      throw new AssertionError("Deliberate failure between successful dispatches");
    }
  }
}
