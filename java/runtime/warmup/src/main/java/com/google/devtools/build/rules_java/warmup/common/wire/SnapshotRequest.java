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

/**
 * Warming JVM -> coordinator: send the current merged state as a Profile reply. Sent once on
 * startup, before reading from the ServerEvent stream.
 */
public final class SnapshotRequest {
  public static final SnapshotRequest INSTANCE = new SnapshotRequest();

  private SnapshotRequest() {}
}
