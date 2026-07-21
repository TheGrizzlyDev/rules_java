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
package com.google.devtools.build.rules_java.warmup.engine;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies preload and compile entries during the warming phase.
 *
 * <p>For each preload entry: computes the SHA-256 of {@code <class>.class} on the classpath. If it
 * matches the profile's expected digest, loads the class (without running {@code <clinit>}). If it
 * mismatches, skips the load and remembers the actual digest so the next {@link
 * com.google.devtools.build.rules_java.warmup.common.wire.TelemetrySample} corrects the
 * coordinator's view.
 *
 * <p>For each compile entry: resolves the target method and hands it to a {@link JitEnqueuer}.
 * When WhiteBox is not available the enqueuer is a no-op — class-load warming still happens.
 */
final class Preloader {

  private static final Logger logger = Logger.getLogger(Preloader.class.getName());

  private final ClassLoader loader;
  private final JitEnqueuer jit;

  /** Class name → digest observed on the classpath when it differed from the profile's. */
  private final Map<String, String> correctedDigests = new ConcurrentHashMap<>();

  Preloader(ClassLoader loader, JitEnqueuer jit) {
    this.loader = loader;
    this.jit = jit;
  }

  void applyPreload(List<Profile.PreloadEntry> entries) {
    int loaded = 0;
    int mismatched = 0;
    int missing = 0;
    for (Profile.PreloadEntry entry : entries) {
      Outcome outcome = preloadOne(entry);
      switch (outcome) {
        case LOADED:
          loaded++;
          break;
        case DIGEST_MISMATCH:
          mismatched++;
          break;
        case NOT_ON_CLASSPATH:
          missing++;
          break;
      }
    }
    logger.info(
        "preload: "
            + loaded
            + " loaded, "
            + mismatched
            + " digest-mismatched, "
            + missing
            + " missing");
  }

  void applyCompile(List<Profile.CompileEntry> entries) {
    if (!jit.isActive()) {
      logger.info("compile: skipping " + entries.size() + " entries — no JIT enqueuer");
      return;
    }
    int enqueued = 0;
    for (Profile.CompileEntry entry : entries) {
      if (enqueueOne(entry)) {
        enqueued++;
      }
    }
    logger.info("compile: " + enqueued + "/" + entries.size() + " methods enqueued");
  }

  /** Class-name → observed digest for entries whose profile digest didn't match reality. */
  Map<String, String> correctedDigests() {
    return correctedDigests;
  }

  private Outcome preloadOne(Profile.PreloadEntry entry) {
    String expected = entry.sha256DigestHex();
    String actual;
    try {
      actual = ClassDigest.forClass(entry.fqClassName(), loader);
    } catch (IOException e) {
      logger.log(Level.FINE, "digest failed for " + entry.fqClassName(), e);
      return Outcome.NOT_ON_CLASSPATH;
    }
    if (actual == null) {
      return Outcome.NOT_ON_CLASSPATH;
    }
    if (!expected.isEmpty() && !expected.equals(actual)) {
      logger.info(
          "digest mismatch for "
              + entry.fqClassName()
              + ": profile="
              + expected
              + " classpath="
              + actual);
      correctedDigests.put(entry.fqClassName(), actual);
      return Outcome.DIGEST_MISMATCH;
    }
    try {
      Class.forName(entry.fqClassName(), /* initialize= */ false, loader);
      return Outcome.LOADED;
    } catch (ClassNotFoundException | LinkageError e) {
      logger.log(Level.FINE, "failed to load " + entry.fqClassName(), e);
      return Outcome.NOT_ON_CLASSPATH;
    }
  }

  private boolean enqueueOne(Profile.CompileEntry entry) {
    Class<?> owner;
    try {
      owner = Class.forName(entry.fqClassName(), /* initialize= */ false, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      logger.log(Level.FINE, "compile: owner missing " + entry.fqClassName(), e);
      return false;
    }
    Method method = findMethod(owner, entry.methodName(), entry.descriptor());
    if (method == null) {
      logger.fine(
          "compile: no method "
              + entry.fqClassName()
              + "#"
              + entry.methodName()
              + entry.descriptor());
      return false;
    }
    jit.enqueue(method, entry.tier());
    return true;
  }

  private static Method findMethod(Class<?> owner, String name, String descriptor) {
    for (Method m : owner.getDeclaredMethods()) {
      if (m.getName().equals(name) && descriptorOf(m).equals(descriptor)) {
        return m;
      }
    }
    return null;
  }

  /** JVMS internal-form descriptor for a reflected method. */
  private static String descriptorOf(Method m) {
    StringBuilder sb = new StringBuilder("(");
    for (Class<?> p : m.getParameterTypes()) {
      appendType(sb, p);
    }
    sb.append(')');
    appendType(sb, m.getReturnType());
    return sb.toString();
  }

  private static void appendType(StringBuilder sb, Class<?> type) {
    if (type.isPrimitive()) {
      sb.append(primitiveDescriptor(type));
    } else if (type.isArray()) {
      sb.append(type.getName().replace('.', '/'));
    } else {
      sb.append('L').append(type.getName().replace('.', '/')).append(';');
    }
  }

  private static char primitiveDescriptor(Class<?> type) {
    if (type == boolean.class) return 'Z';
    if (type == byte.class) return 'B';
    if (type == char.class) return 'C';
    if (type == short.class) return 'S';
    if (type == int.class) return 'I';
    if (type == long.class) return 'J';
    if (type == float.class) return 'F';
    if (type == double.class) return 'D';
    if (type == void.class) return 'V';
    throw new AssertionError("not primitive: " + type);
  }

  private enum Outcome {
    LOADED,
    DIGEST_MISMATCH,
    NOT_ON_CLASSPATH,
  }
}
