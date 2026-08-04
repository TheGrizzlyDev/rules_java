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
package com.google.devtools.build.java.testrunner.extension;

import java.util.Optional;

/**
 * Key-value store shared across all JVMs spawned by a coordinator. Values are typed; reads of a
 * mismatched type throw {@link IllegalStateException}.
 */
public interface Store {
  Optional<String> getString(String key);

  Optional<Boolean> getBoolean(String key);

  Optional<Long> getLong(String key);

  Optional<Double> getDouble(String key);

  void set(String key, String value);

  void set(String key, boolean value);

  void set(String key, long value);

  void set(String key, double value);
}
