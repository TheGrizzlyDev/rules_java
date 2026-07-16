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
package com.google.devtools.build.rules_java.warmup.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * On-disk profile schema. Mirrors the proto {@code Profile} message 1:1 so coordinator and engine
 * can use a single in-memory representation regardless of whether state arrived from disk or wire.
 */
public final class Profile {

  /**
   * (fully qualified class name, lowercase hex SHA-256 of class file bytes). The hex form is the
   * canonical representation everywhere — on disk, in-memory, and as the comparison key against
   * the digest computed by the warming JVM.
   */
  public static final class PreloadEntry {
    private final String fqClassName;
    private final String sha256DigestHex;

    public PreloadEntry(String fqClassName, String sha256DigestHex) {
      this.fqClassName = Objects.requireNonNull(fqClassName, "fqClassName");
      this.sha256DigestHex = Objects.requireNonNull(sha256DigestHex, "sha256DigestHex");
    }

    public String fqClassName() {
      return fqClassName;
    }

    public String sha256DigestHex() {
      return sha256DigestHex;
    }
  }

  /** (class, method, JVMS internal-form descriptor, advisory tier). */
  public static final class CompileEntry {
    private final String fqClassName;
    private final String methodName;
    private final String descriptor;
    private final int tier;

    public CompileEntry(String fqClassName, String methodName, String descriptor, int tier) {
      this.fqClassName = Objects.requireNonNull(fqClassName, "fqClassName");
      this.methodName = Objects.requireNonNull(methodName, "methodName");
      this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
      this.tier = tier;
    }

    public String fqClassName() {
      return fqClassName;
    }

    public String methodName() {
      return methodName;
    }

    public String descriptor() {
      return descriptor;
    }

    public int tier() {
      return tier;
    }
  }

  private final List<PreloadEntry> preload;
  private final List<CompileEntry> compile;

  public Profile(List<PreloadEntry> preload, List<CompileEntry> compile) {
    this.preload = Collections.unmodifiableList(new ArrayList<>(preload));
    this.compile = Collections.unmodifiableList(new ArrayList<>(compile));
  }

  public List<PreloadEntry> preload() {
    return preload;
  }

  public List<CompileEntry> compile() {
    return compile;
  }
}
