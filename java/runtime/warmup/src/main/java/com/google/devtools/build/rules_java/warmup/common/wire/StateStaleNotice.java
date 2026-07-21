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
 * Coordinator -> warming JVM: a class's reported digest from a running JVM diverges from the
 * digest under which it was preloaded. The warming JVM evicts that class so the next reference
 * reloads fresh from the current classpath.
 */
public final class StateStaleNotice implements ServerEvent {
  private final String fqClassName;
  private final String warmedDigestHex;
  private final String reportedDigestHex;
  private final String reason;

  public StateStaleNotice(
      String fqClassName, String warmedDigestHex, String reportedDigestHex, String reason) {
    this.fqClassName = Objects.requireNonNull(fqClassName, "fqClassName");
    this.warmedDigestHex = Objects.requireNonNull(warmedDigestHex, "warmedDigestHex");
    this.reportedDigestHex = Objects.requireNonNull(reportedDigestHex, "reportedDigestHex");
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public String fqClassName() {
    return fqClassName;
  }

  public String warmedDigestHex() {
    return warmedDigestHex;
  }

  public String reportedDigestHex() {
    return reportedDigestHex;
  }

  public String reason() {
    return reason;
  }
}
