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

import com.google.devtools.build.java.testrunner.wire.StoreValue;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coordinator-owned key/value map that backs {@code Store} calls made by extensions in child JVMs.
 * State lives for the coordinator process's lifetime and is shared across all children it spawns.
 */
final class CoordinatorStore {

  private final ConcurrentMap<String, StoreValue> map = new ConcurrentHashMap<>();

  Optional<StoreValue> get(String key) {
    return Optional.ofNullable(map.get(key));
  }

  void set(String key, StoreValue value) {
    map.put(key, value);
  }
}
