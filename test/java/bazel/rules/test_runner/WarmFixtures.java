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

import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Different suites deliberately share classloader and application state. */
public final class WarmFixtures {
  private static final String token = UUID.randomUUID().toString();
  private static int executions;

  private static void exercise(String marker) {
    String supplied = System.getenv("TEST_WARM_MARKER");
    if (supplied != null && !marker.equals(supplied)) {
      throw new AssertionError("Previous request environment leaked: " + supplied);
    }
    if (++executions > 1 && !"retained".equals(System.getProperty("warm.proof.state"))) {
      throw new AssertionError("Warm system property was reset");
    }
    System.setProperty("warm.proof.state", "retained");
    System.out.println("WARM_STATE marker=" + marker + " token=" + token + " count=" + executions);
  }

  @RunWith(JUnit4.class)
  public static final class First {
    @Test
    public void firstTest() {
      exercise("first");
    }
  }

  @RunWith(JUnit4.class)
  public static final class Second {
    @Test
    public void secondTest() {
      exercise("second");
    }
  }

  @RunWith(JUnit4.class)
  public static final class Failure {
    @Test
    public void failingTest() {
      exercise("failure");
      throw new AssertionError("Deliberate failure between successful dispatches");
    }
  }
}
