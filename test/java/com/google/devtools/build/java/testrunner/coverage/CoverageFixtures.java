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
package com.google.devtools.build.java.testrunner.coverage;

import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Two tests cover distinct methods of one retained class to expose stale JaCoCo probes. */
public final class CoverageFixtures {
  private static final String token = UUID.randomUUID().toString();
  private static int executions;

  public static int firstBranch() {
    return 1;
  }

  public static int secondBranch() {
    return 2;
  }

  private static void check(int actual, int expected) {
    if (actual != expected) {
      throw new AssertionError(actual);
    }
    System.out.println("COVERAGE_WARM token=" + token + " count=" + (++executions));
  }

  @RunWith(JUnit4.class)
  public static final class First {
    @Test
    public void first() {
      check(firstBranch(), 1);
    }
  }

  @RunWith(JUnit4.class)
  public static final class Second {
    @Test
    public void second() {
      check(secondBranch(), 2);
    }
  }
}
