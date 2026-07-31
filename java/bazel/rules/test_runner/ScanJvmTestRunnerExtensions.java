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

import com.google.devtools.build.java.testrunner.persistent_worker.PersistentWorker;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ScanJvmTestRunnerExtensions {

  private static final String EXTENSIONS_RESOURCE =
      "META-INF/persistent-test-runner-extensions.txt";

  private ScanJvmTestRunnerExtensions() {}

  public static void main(String[] args) throws Exception {
    PersistentWorker.run(args, ScanJvmTestRunnerExtensions::scan);
  }

  static int scan(List<String> args, PrintStream stdout, PrintStream stderr) throws IOException {
    if (args.size() != 2) {
      stderr.println("Usage: ScanJvmTestRunnerExtensions <input.jar> <output.txt>");
      return 2;
    }
    Path input = Paths.get(args.get(0));
    Path output = Paths.get(args.get(1));

    try (JarFile jar = new JarFile(input.toFile())) {
      JarEntry entry = jar.getJarEntry(EXTENSIONS_RESOURCE);
      if (entry == null) {
        Files.write(output, new byte[0]);
        return 0;
      }
      try (InputStream in = jar.getInputStream(entry)) {
        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return 0;
  }
}
