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
package com.google.devtools.build.rules_java.warmup.coordinator;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import com.google.devtools.build.rules_java.warmup.common.wire.TelemetrySample;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator's merged view of what past engines observed: how often each class was loaded, how
 * often each method appeared hot, and the last digest reported for each class.
 *
 * <p>{@link #snapshot(int, int, int)} returns a {@link Profile} ranked by frequency, ties broken
 * by name for determinism. Callers use this to answer {@code SnapshotRequest}.
 *
 * <p>Thread-safe: all state lives in concurrent maps. Ranking uses a snapshot iterator, so
 * concurrent merges won't corrupt the output but may or may not be reflected in it.
 */
final class FrequencyProfile {

  private final ConcurrentHashMap<String, ClassStats> classes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<MethodKey, MethodStats> methods = new ConcurrentHashMap<>();

  /** Merges a sample into the running counts and updates last-seen digests. */
  void merge(TelemetrySample sample) {
    for (Profile.PreloadEntry entry : sample.loadedClasses()) {
      classes
          .computeIfAbsent(entry.fqClassName(), k -> new ClassStats())
          .observe(entry.sha256DigestHex());
    }
    for (Profile.CompileEntry entry : sample.hotMethods()) {
      MethodKey key =
          new MethodKey(entry.fqClassName(), entry.methodName(), entry.descriptor(), entry.tier());
      methods.computeIfAbsent(key, k -> new MethodStats()).increment();
    }
  }

  /** Returns a Profile with the top-{@code topClasses}/{@code topMethods} entries by frequency. */
  Profile snapshot(int topClasses, int topMethods, int minCount) {
    List<Profile.PreloadEntry> preload = new ArrayList<>();
    List<Map.Entry<String, ClassStats>> classList = new ArrayList<>(classes.entrySet());
    classList.sort(
        Comparator.<Map.Entry<String, ClassStats>>comparingLong(e -> -e.getValue().count.sum())
            .thenComparing(Map.Entry::getKey));
    for (Map.Entry<String, ClassStats> e : classList) {
      if (preload.size() >= topClasses) {
        break;
      }
      if (e.getValue().count.sum() < minCount) {
        continue;
      }
      String digest = e.getValue().lastDigestHex;
      preload.add(new Profile.PreloadEntry(e.getKey(), digest == null ? "" : digest));
    }

    List<Profile.CompileEntry> compile = new ArrayList<>();
    List<Map.Entry<MethodKey, MethodStats>> methodList = new ArrayList<>(methods.entrySet());
    methodList.sort(
        Comparator.<Map.Entry<MethodKey, MethodStats>>comparingLong(e -> -e.getValue().count.sum())
            .thenComparing(e -> e.getKey().className)
            .thenComparing(e -> e.getKey().methodName)
            .thenComparing(e -> e.getKey().descriptor));
    for (Map.Entry<MethodKey, MethodStats> e : methodList) {
      if (compile.size() >= topMethods) {
        break;
      }
      if (e.getValue().count.sum() < minCount) {
        continue;
      }
      MethodKey k = e.getKey();
      compile.add(new Profile.CompileEntry(k.className, k.methodName, k.descriptor, k.tier));
    }

    return new Profile(preload, compile);
  }

  int classCount() {
    return classes.size();
  }

  int methodCount() {
    return methods.size();
  }

  /** Last-seen digest for {@code fqClassName}, or {@code null} if never observed with a digest. */
  String lastDigest(String fqClassName) {
    ClassStats stats = classes.get(fqClassName);
    return stats == null ? null : stats.lastDigestHex;
  }

  private static final class ClassStats {
    final java.util.concurrent.atomic.LongAdder count = new java.util.concurrent.atomic.LongAdder();
    volatile String lastDigestHex;

    void observe(String digest) {
      count.increment();
      if (digest != null && !digest.isEmpty()) {
        lastDigestHex = digest;
      }
    }
  }

  private static final class MethodStats {
    final java.util.concurrent.atomic.LongAdder count = new java.util.concurrent.atomic.LongAdder();

    void increment() {
      count.increment();
    }
  }

  private static final class MethodKey {
    final String className;
    final String methodName;
    final String descriptor;
    final int tier;

    MethodKey(String className, String methodName, String descriptor, int tier) {
      this.className = className;
      this.methodName = methodName;
      this.descriptor = descriptor;
      this.tier = tier;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof MethodKey)) {
        return false;
      }
      MethodKey k = (MethodKey) o;
      return tier == k.tier
          && className.equals(k.className)
          && methodName.equals(k.methodName)
          && descriptor.equals(k.descriptor);
    }

    @Override
    public int hashCode() {
      return Objects.hash(className, methodName, descriptor, tier);
    }
  }
}
