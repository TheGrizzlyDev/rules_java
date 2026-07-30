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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Config {
  final String workspacePrefix;
  final String javabin;
  final String mainClass;
  final String coverageMainClass;
  final String driverMain;
  final List<String> jvmFlags;
  final List<String> classpath;
  final List<String> driverClasspath;

  private Config(
      String workspacePrefix,
      String javabin,
      String mainClass,
      String coverageMainClass,
      String driverMain,
      List<String> jvmFlags,
      List<String> classpath,
      List<String> driverClasspath) {
    this.workspacePrefix = workspacePrefix;
    this.javabin = javabin;
    this.mainClass = mainClass;
    this.coverageMainClass = coverageMainClass;
    this.driverMain = driverMain;
    this.jvmFlags = Collections.unmodifiableList(jvmFlags);
    this.classpath = Collections.unmodifiableList(classpath);
    this.driverClasspath = Collections.unmodifiableList(driverClasspath);
  }

  static Config parse(Path file) throws IOException {
    String workspacePrefix = "";
    String javabin = null;
    String mainClass = null;
    String coverageMainClass = null;
    String driverMain = null;
    List<String> jvmFlags = new ArrayList<>();
    List<String> classpath = new ArrayList<>();
    List<String> driverClasspath = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      int eq = line.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = line.substring(0, eq);
      String value = line.substring(eq + 1);
      switch (key) {
        case "workspace_prefix":
          workspacePrefix = value;
          break;
        case "javabin":
          javabin = value;
          break;
        case "main_class":
          mainClass = value;
          break;
        case "coverage_main_class":
          coverageMainClass = value;
          break;
        case "driver_main":
          driverMain = value;
          break;
        case "jvm_flag":
          jvmFlags.add(value);
          break;
        case "classpath_entry":
          classpath.add(value);
          break;
        case "driver_classpath_entry":
          driverClasspath.add(value);
          break;
        default:
          throw new IllegalArgumentException("unknown config key: " + key);
      }
    }
    if (javabin == null || mainClass == null) {
      throw new IllegalStateException("javabin and main_class are required in " + file);
    }
    return new Config(
        workspacePrefix,
        javabin,
        mainClass,
        coverageMainClass,
        driverMain,
        jvmFlags,
        classpath,
        driverClasspath);
  }
}
