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

import java.util.Objects;

/**
 * Promotes the warming JVM to test-running mode. The warming JVM cancels its update stream, sets
 * frozen=true, and waits for a test world.
 */
public final class PromoteRequest implements ServerEvent {
  private final String warmingJvmId;
  private final String reason;

  public PromoteRequest(String warmingJvmId, String reason) {
    this.warmingJvmId = Objects.requireNonNull(warmingJvmId, "warmingJvmId");
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public String warmingJvmId() {
    return warmingJvmId;
  }

  public String reason() {
    return reason;
  }
}
