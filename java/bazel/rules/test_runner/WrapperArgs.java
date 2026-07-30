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

import java.util.ArrayList;
import java.util.List;

/** Parses the wrapper-script-style flags that java_stub_template.txt understands. */
final class WrapperArgs {
  String jvmDebugPort;
  String jvmDebugSuspend = "y";
  String mainAdvice;
  String mainAdviceClasspath;
  final List<String> jvmFlagsCmdline = new ArrayList<>();
  boolean singlejar;
  boolean printJavabin;
  Integer classpathLimit;
  final List<String> programArgs = new ArrayList<>();

  private WrapperArgs() {}

  static WrapperArgs parse(List<String> args) {
    WrapperArgs w = new WrapperArgs();
    String defaultDebugPort = System.getenv("DEFAULT_JVM_DEBUG_PORT");
    String defaultDebugSuspend = System.getenv("DEFAULT_JVM_DEBUG_SUSPEND");
    if (defaultDebugSuspend != null) {
      w.jvmDebugSuspend = defaultDebugSuspend;
    }

    boolean stopParsing = false;
    for (String arg : args) {
      if (arg.startsWith("--wrapper_script_flag=")) {
        w.processFlag(arg.substring("--wrapper_script_flag=".length()), defaultDebugPort);
      } else if (!stopParsing && w.tryProcessFlag(arg, defaultDebugPort)) {
        // consumed
      } else {
        stopParsing = true;
        w.programArgs.add(arg);
      }
    }
    return w;
  }

  private void processFlag(String flag, String defaultDebugPort) {
    if (!tryProcessFlag(flag, defaultDebugPort)) {
      throw new IllegalArgumentException("invalid wrapper argument: " + flag);
    }
  }

  private boolean tryProcessFlag(String flag, String defaultDebugPort) {
    if (flag.equals("--debug")) {
      jvmDebugPort = defaultDebugPort != null ? defaultDebugPort : "5005";
    } else if (flag.startsWith("--debug=")) {
      jvmDebugPort = flag.substring("--debug=".length());
    } else if (flag.startsWith("--main_advice=")) {
      mainAdvice = flag.substring("--main_advice=".length());
    } else if (flag.startsWith("--main_advice_classpath=")) {
      mainAdviceClasspath = flag.substring("--main_advice_classpath=".length());
    } else if (flag.startsWith("--jvm_flag=")) {
      jvmFlagsCmdline.add(flag.substring("--jvm_flag=".length()));
    } else if (flag.startsWith("--jvm_flags=")) {
      String value = flag.substring("--jvm_flags=".length());
      for (String tok : value.split("\\s+")) {
        if (!tok.isEmpty()) {
          jvmFlagsCmdline.add(tok);
        }
      }
    } else if (flag.equals("--singlejar")) {
      singlejar = true;
    } else if (flag.equals("--print_javabin")) {
      printJavabin = true;
    } else if (flag.startsWith("--classpath_limit=")) {
      classpathLimit = Integer.parseInt(flag.substring("--classpath_limit=".length()));
    } else {
      return false;
    }
    return true;
  }
}
