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

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Opt-in hanging suite for cancellation through an actual Bazel invocation. */
@RunWith(JUnit4.class)
public final class BazelCancellationFixture {
  @Test
  public void run() throws Exception {
    String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    System.out.println("BAZEL_CANCELLATION_CHILD pid=" + pid);
    String marker = System.getenv("TEST_CANCELLATION_STARTED");
    if (marker != null) {
      Files.write(Paths.get(marker), pid.getBytes(StandardCharsets.UTF_8));
    }
    if ("hang".equals(System.getenv("TEST_CANCELLATION_MODE"))) {
      while (true) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignored) {
          // Require cancellation to retire the JVM even when test code ignores interruption.
        }
      }
    }
  }
}
