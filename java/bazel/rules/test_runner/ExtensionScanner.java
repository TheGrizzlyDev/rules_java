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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ExtensionScanner {

  private static final String EXTENSIONS_RESOURCE =
      "META-INF/persistent-test-runner-extensions.txt";

  private ExtensionScanner() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("Usage: ExtensionScanner <input.jar> <output.txt>");
      System.exit(2);
    }
    Path input = Paths.get(args[0]);
    Path output = Paths.get(args[1]);

    try (JarFile jar = new JarFile(input.toFile())) {
      JarEntry entry = jar.getJarEntry(EXTENSIONS_RESOURCE);
      if (entry == null) {
        Files.write(output, new byte[0]);
        return;
      }
      try (InputStream in = jar.getInputStream(entry)) {
        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
