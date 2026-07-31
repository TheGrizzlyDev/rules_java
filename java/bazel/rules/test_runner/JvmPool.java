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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class JvmPool {

  private final int capacity;
  private final List<JvmProcess> jvms = new ArrayList<>();
  private long clock;

  JvmPool(int capacity) {
    this.capacity = capacity;
  }

  synchronized JvmProcess tryReuse(List<JvmProcess.ClasspathEntry> requested) {
    gcExcessIdle();
    for (JvmProcess jvm : jvms) {
      if (jvm.isBusy()) {
        continue;
      }
      if (!isCompatible(jvm.classpath, requested)) {
        continue;
      }
      if (jvm.tryClaim()) {
        touch(jvm);
        return jvm;
      }
    }
    return null;
  }

  synchronized void reserveSlot() {
    // If we're over capacity because all JVMs are busy, allow spawning anyway.
    // Excess idle JVMs will be reclaimed by gcExcessIdle() on the next release/tryReuse.
    gcExcessIdle();
  }

  synchronized void register(JvmProcess jvm) {
    touch(jvm);
    jvms.add(jvm);
  }

  synchronized void release(JvmProcess jvm) {
    jvm.release();
    gcExcessIdle();
  }

  private void gcExcessIdle() {
    reapDead();
    while (jvms.size() > capacity) {
      if (!evictLruIdle()) {
        return;
      }
    }
  }

  private void touch(JvmProcess jvm) {
    jvm.lastUsedAt = ++clock;
  }

  private void reapDead() {
    Iterator<JvmProcess> it = jvms.iterator();
    while (it.hasNext()) {
      JvmProcess jvm = it.next();
      if (!jvm.process.isAlive()) {
        it.remove();
      }
    }
  }

  private boolean evictLruIdle() {
    JvmProcess victim = null;
    for (JvmProcess jvm : jvms) {
      if (jvm.isBusy()) {
        continue;
      }
      if (victim == null || jvm.lastUsedAt < victim.lastUsedAt) {
        victim = jvm;
      }
    }
    if (victim == null) {
      return false;
    }
    victim.process.destroy();
    jvms.remove(victim);
    return true;
  }

  private static boolean isCompatible(
      List<JvmProcess.ClasspathEntry> existing, List<JvmProcess.ClasspathEntry> requested) {
    for (JvmProcess.ClasspathEntry req : requested) {
      for (JvmProcess.ClasspathEntry ex : existing) {
        if (req.label.equals(ex.label) || req.path.equals(ex.path)) {
          if (!req.sha256.equals(ex.sha256)) {
            return false;
          }
        }
      }
    }
    return true;
  }
}
