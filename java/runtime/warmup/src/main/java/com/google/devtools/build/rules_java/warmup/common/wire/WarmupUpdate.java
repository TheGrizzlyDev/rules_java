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

/** Coordinator -> warming JVM: incremental preload and JIT-enqueue instructions. */
public final class WarmupUpdate implements ServerEvent {
  private final List<Profile.PreloadEntry> preload;
  private final List<Profile.CompileEntry> compile;

  public WarmupUpdate(List<Profile.PreloadEntry> preload, List<Profile.CompileEntry> compile) {
    this.preload = Collections.unmodifiableList(new ArrayList<>(preload));
    this.compile = Collections.unmodifiableList(new ArrayList<>(compile));
  }

  public List<Profile.PreloadEntry> preload() {
    return preload;
  }

  public List<Profile.CompileEntry> compile() {
    return compile;
  }
}
