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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Reports actual process identities for the cross-target sharing integration rig. */
public final class CoordinatorSharingTest {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !(args[0].equals("first") || args[0].equals("second"))) {
      throw new AssertionError("Wrong request args: " + Arrays.toString(args));
    }
    String marker = args[0];
    if (!marker.equals(System.getenv("TEST_SHARING_MARKER"))) {
      throw new AssertionError("Wrong per-target environment for " + marker);
    }
    String target = "CoordinatorSharing" + (marker.equals("first") ? "First" : "Second");
    if (!System.getenv("TEST_TARGET").endsWith(":" + target)) {
      throw new AssertionError("Wrong TEST_TARGET: " + System.getenv("TEST_TARGET"));
    }
    String child = null;
    String coordinator = null;
    for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
      if (line.startsWith("Pid:")) {
        child = line.substring(4).trim();
      } else if (line.startsWith("PPid:")) {
        coordinator = line.substring(5).trim();
      }
    }
    if (child == null || coordinator == null || coordinator.equals("0")) {
      throw new AssertionError("Could not read process identity");
    }
    String xml = "<testsuite name=\"" + marker
        + "\" tests=\"1\" failures=\"0\"><testcase name=\"sharing\"/></testsuite>";
    Files.write(Paths.get(System.getenv("XML_OUTPUT_FILE")), xml.getBytes(StandardCharsets.UTF_8));
    System.out.println("SHARING " + marker + " coordinator=" + coordinator + " child=" + child);
  }
}
