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
package com.google.devtools.build.java.testrunner.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinator to child: another test in the existing warm classloader. */
public final class TestStart implements Message {
  public final List<String> classpath;
  public final List<String> arguments;
  public final Map<String, String> environment;
  public final Map<String, String> properties;

  public TestStart(
      List<String> classpath,
      List<String> arguments,
      Map<String, String> environment,
      Map<String, String> properties) {
    this.classpath = Collections.unmodifiableList(new ArrayList<>(classpath));
    this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
    this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }
}
