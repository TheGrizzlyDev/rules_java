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
package com.google.devtools.build.rules_java.warmup.common.wire;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Running test JVM -> coordinator: a batch of observations about classes loaded and methods that
 * proved hot during the run. Every class resolved on the running side is reported along with its
 * digest; the coordinator ranks by frequency across runs to decide what to preload next.
 */
public final class TelemetrySample {
  private final List<Profile.PreloadEntry> loadedClasses;
  private final List<Profile.CompileEntry> hotMethods;

  public TelemetrySample(
      List<Profile.PreloadEntry> loadedClasses, List<Profile.CompileEntry> hotMethods) {
    this.loadedClasses = Collections.unmodifiableList(new ArrayList<>(loadedClasses));
    this.hotMethods = Collections.unmodifiableList(new ArrayList<>(hotMethods));
  }

  public List<Profile.PreloadEntry> loadedClasses() {
    return loadedClasses;
  }

  public List<Profile.CompileEntry> hotMethods() {
    return hotMethods;
  }
}
