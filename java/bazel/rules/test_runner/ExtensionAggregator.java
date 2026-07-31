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
import java.nio.file.Paths;
import java.util.TreeSet;

public final class ExtensionAggregator {

  private ExtensionAggregator() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      System.err.println("Usage: ExtensionAggregator <output> [<input>...]");
      System.exit(2);
    }
    Path output = Paths.get(args[0]);

    TreeSet<String> classes = new TreeSet<>();
    for (int i = 1; i < args.length; i++) {
      for (String line : Files.readAllLines(Paths.get(args[i]), StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) {
          classes.add(trimmed);
        }
      }
    }

    StringBuilder sb = new StringBuilder();
    for (String c : classes) {
      sb.append(c).append('\n');
    }
    Files.write(output, sb.toString().getBytes(StandardCharsets.UTF_8));
  }
}
