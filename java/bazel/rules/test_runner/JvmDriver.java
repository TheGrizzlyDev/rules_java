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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class JvmDriver {

  private static final String TEST_MAIN_PROP = "bazel.persistent.test_main";
  private static final String STATUS_FILE_PROP = "bazel.persistent.status_file";

  private JvmDriver() {}

  public static void main(String[] args) throws Exception {
    String testMain = System.getProperty(TEST_MAIN_PROP);
    String statusFile = System.getProperty(STATUS_FILE_PROP);
    if (testMain == null || statusFile == null) {
      throw new IllegalStateException(
          "JvmDriver requires -D" + TEST_MAIN_PROP + " and -D" + STATUS_FILE_PROP);
    }

    int exitCode;
    try {
      Method main = Class.forName(testMain).getMethod("main", String[].class);
      main.invoke(null, (Object) args);
      exitCode = 0;
    } catch (InvocationTargetException e) {
      // TODO: intercept System.exit(N) so the test's exit code propagates instead of always 1.
      Throwable cause = e.getCause();
      (cause != null ? cause : e).printStackTrace();
      exitCode = 1;
    } catch (Throwable t) {
      t.printStackTrace();
      exitCode = 1;
    }

    writeStatus(Paths.get(statusFile), exitCode);
    System.out.flush();
    System.err.flush();

    Thread.currentThread().join();
  }

  private static void writeStatus(Path path, int exitCode) throws IOException {
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    Files.write(tmp, Integer.toString(exitCode).getBytes(StandardCharsets.UTF_8));
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
}
