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

import java.util.Arrays;
import java.util.List;

/** Checks rule args and optional command-line args at the actual java_test entry point. */
public final class NativeArgumentsTest {
  private static final List<String> RULE_ARGS = Arrays.asList(
      "rule value", "", "'quoted'", "double\"quote", "back\\slash", "@missing-file",
      "@@literal", "--config=program-option", "--persistent_worker", "--", "last");
  private static final List<String> CLI_ARGS = Arrays.asList(
      "cli value", "@cli-missing", "", "cli'quote", "cli\\slash");

  public static void main(String[] args) {
    List<String> actual = Arrays.asList(args);
    if (actual.size() < RULE_ARGS.size()
        || !actual.subList(0, RULE_ARGS.size()).equals(RULE_ARGS)) {
      throw new AssertionError("Changed java_test.args: " + actual);
    }
    List<String> commandLine = actual.subList(RULE_ARGS.size(), actual.size());
    List<String> expectedCommandLine = "1".equals(System.getenv("PERSISTENT_EXPECT_CLI_ARGS"))
        ? CLI_ARGS : java.util.Collections.emptyList();
    if (!commandLine.equals(expectedCommandLine)) {
      throw new AssertionError("Changed --test_arg values: " + commandLine
          + "; expected: " + expectedCommandLine);
    }
    if (!"rule".equals(System.getProperty("persistent.argument.marker"))) {
      throw new AssertionError("Rule's JVM wrapper flag was not applied to the test JVM");
    }
    System.out.println("PASS native java_test.args and " + commandLine.size() + " --test_arg values");
  }
}
