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
  public static final class Failure {
    @Test
    public void failingTest() throws Exception {
      exercise("failure");
      throw new AssertionError("Deliberate failure between successful dispatches");
    }
  }
}
