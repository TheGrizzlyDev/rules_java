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

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link JitEnqueuer} backed by HotSpot's {@code WhiteBox} API. Resolved reflectively so the
 * engine doesn't take a hard dependency on {@code whitebox.jar}.
 *
 * <p>Prefers {@code jdk.test.whitebox.WhiteBox} (JDK 20+), falls back to {@code
 * sun.hotspot.WhiteBox} (older JDKs).
 */
final class WhiteBoxJitEnqueuer implements JitEnqueuer {

  private static final Logger logger = Logger.getLogger(WhiteBoxJitEnqueuer.class.getName());

  private final Object whiteBox;
  private final Method enqueueMethod;

  private WhiteBoxJitEnqueuer(Object whiteBox, Method enqueueMethod) {
    this.whiteBox = whiteBox;
    this.enqueueMethod = enqueueMethod;
  }

  static WhiteBoxJitEnqueuer tryCreate() throws ReflectiveOperationException {
    Class<?> wbClass;
    try {
      wbClass = Class.forName("jdk.test.whitebox.WhiteBox");
    } catch (ClassNotFoundException e) {
      wbClass = Class.forName("sun.hotspot.WhiteBox");
    }
    Method getWhiteBox = wbClass.getMethod("getWhiteBox");
    Object wb = getWhiteBox.invoke(null);
    Method enqueue = wbClass.getMethod("enqueueMethodForCompilation", Method.class, int.class);
    return new WhiteBoxJitEnqueuer(wb, enqueue);
  }

  @Override
  public void enqueue(Method method, int tier) {
    try {
      enqueueMethod.invoke(whiteBox, method, tier);
    } catch (ReflectiveOperationException e) {
      logger.log(Level.FINE, "WhiteBox enqueue failed for " + method + " tier " + tier, e);
    }
  }

  @Override
  public boolean isActive() {
    return true;
  }
}
