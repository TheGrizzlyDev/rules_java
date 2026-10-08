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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Prepares the child environment for test executions that bypass test-setup.sh. */
final class TestEnvironment {
  private TestEnvironment() {}

  static Map<String, String> prepare(Map<String, String> source, Path execRoot) throws IOException {
    Map<String, String> environment = new HashMap<>(source);
    environment.put("BAZEL_TEST", "1");
    // test-setup.sh normally supplies these aliases and locates the manifest.
    // Preserve the original target's runfiles directory, never the worker's.
    String runfilesDir = environment.get("TEST_SRCDIR");
    if (runfilesDir != null && !runfilesDir.isEmpty()) {
      for (String name : Arrays.asList("RUNFILES_DIR", "JAVA_RUNFILES", "PYTHON_RUNFILES")) {
        if (environment.get(name) == null || environment.get(name).isEmpty()) {
          environment.put(name, runfilesDir);
        }
      }
      String manifest = environment.get("RUNFILES_MANIFEST_FILE");
      if (manifest == null || manifest.isEmpty()) {
        Path inTree = execRoot.resolve(runfilesDir).resolve("MANIFEST");
        Path sibling = execRoot.resolve(runfilesDir + "_manifest");
        environment.put(
            "RUNFILES_MANIFEST_FILE",
            (!Files.isRegularFile(inTree) && Files.isRegularFile(sibling) ? sibling : inTree)
                .toString());
      }
    }
    for (String name :
        Arrays.asList(
            "TEST_TMPDIR",
            "TEST_SRCDIR",
            "RUNFILES_DIR",
            "JAVA_RUNFILES",
            "PYTHON_RUNFILES",
            "RUNFILES_MANIFEST_FILE",
            "XML_OUTPUT_FILE",
            "TEST_SHARD_STATUS_FILE",
            "TEST_PREMATURE_EXIT_FILE",
            "TEST_WARNINGS_OUTPUT_FILE",
            "TEST_LOGSPLITTER_OUTPUT_FILE",
            "TEST_INFRASTRUCTURE_FAILURE_FILE",
            "TEST_UNUSED_RUNFILES_LOG_FILE",
            "TEST_UNDECLARED_OUTPUTS_DIR",
            "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR")) {
      String value = environment.get(name);
      if (value != null && !value.isEmpty()) {
        environment.put(name, execRoot.resolve(value).toAbsolutePath().normalize().toString());
      }
    }
    for (String name :
        Arrays.asList(
            "TEST_TMPDIR",
            "TEST_UNDECLARED_OUTPUTS_DIR",
            "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR")) {
      String value = environment.get(name);
      if (value != null && !value.isEmpty()) {
        Files.createDirectories(Paths.get(value));
      }
    }
    for (String name : Arrays.asList("XML_OUTPUT_FILE", "TEST_SHARD_STATUS_FILE")) {
      String value = environment.get(name);
      if (value != null && !value.isEmpty()) {
        Files.createDirectories(Paths.get(value).getParent());
      }
    }
    String tmpdir = environment.get("TEST_TMPDIR");
    if (tmpdir != null && !tmpdir.isEmpty()) {
      environment.put("GTEST_TMP_DIR", tmpdir);
    }
    String shards = environment.get("TEST_TOTAL_SHARDS");
    if (shards != null && !shards.equals("0")) {
      environment.put("GTEST_TOTAL_SHARDS", shards);
      String shard = environment.get("TEST_SHARD_INDEX");
      if (shard != null) {
        environment.put("GTEST_SHARD_INDEX", shard);
      }
    }
    return Collections.unmodifiableMap(environment);
  }
}
