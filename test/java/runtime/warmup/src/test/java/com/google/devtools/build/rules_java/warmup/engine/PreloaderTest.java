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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Preloader}. */
@RunWith(JUnit4.class)
public final class PreloaderTest {

  private static String digest(String fqName) throws IOException {
    return ClassDigest.forClass(fqName, PreloaderTest.class.getClassLoader());
  }

  @Test
  public void loadsClassWhenDigestMatches() throws Exception {
    // A class that isn't going to be initialized by anyone else in this test process.
    String target = Fixture.class.getName();
    String expected = digest(target);
    assertThat(expected).isNotNull();

    RecordingJit jit = new RecordingJit();
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyPreload(Collections.singletonList(new Profile.PreloadEntry(target, expected)));

    assertThat(p.correctedDigests()).isEmpty();
  }

  @Test
  public void recordsCorrectionWhenDigestMismatches() throws Exception {
    String target = Fixture.class.getName();
    String actual = digest(target);

    RecordingJit jit = new RecordingJit();
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyPreload(
        Collections.singletonList(new Profile.PreloadEntry(target, "stale-digest-hex")));

    assertThat(p.correctedDigests()).containsExactly(target, actual);
  }

  @Test
  public void skipsClassNotOnClasspath() {
    RecordingJit jit = new RecordingJit();
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyPreload(
        Collections.singletonList(new Profile.PreloadEntry("com.nonexistent.Bogus", "anything")));

    assertThat(p.correctedDigests()).isEmpty();
  }

  @Test
  public void emptyExpectedDigestIsTreatedAsWildcard() throws Exception {
    String target = Fixture.class.getName();
    RecordingJit jit = new RecordingJit();
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyPreload(Collections.singletonList(new Profile.PreloadEntry(target, "")));

    assertThat(p.correctedDigests()).isEmpty();
  }

  @Test
  public void jitEnqueuerCalledForResolvableMethod() {
    RecordingJit jit = new RecordingJit(/* active= */ true);
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyCompile(
        Collections.singletonList(
            new Profile.CompileEntry(Fixture.class.getName(), "hotMethod", "()V", 4)));

    assertThat(jit.enqueued).hasSize(1);
    Method m = jit.enqueued.get(0).method;
    assertThat(m.getName()).isEqualTo("hotMethod");
    assertThat(jit.enqueued.get(0).tier).isEqualTo(4);
  }

  @Test
  public void jitSkippedWhenEnqueuerInactive() {
    RecordingJit jit = new RecordingJit(/* active= */ false);
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyCompile(
        Collections.singletonList(
            new Profile.CompileEntry(Fixture.class.getName(), "hotMethod", "()V", 4)));

    assertThat(jit.enqueued).isEmpty();
  }

  @Test
  public void jitSkipsMissingMethods() {
    RecordingJit jit = new RecordingJit(/* active= */ true);
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyCompile(
        Arrays.asList(
            new Profile.CompileEntry(Fixture.class.getName(), "doesNotExist", "()V", 4),
            new Profile.CompileEntry(Fixture.class.getName(), "hotMethod", "()V", 4)));

    assertThat(jit.enqueued).hasSize(1);
    assertThat(jit.enqueued.get(0).method.getName()).isEqualTo("hotMethod");
  }

  @Test
  public void jitResolvesMethodBySignature() {
    RecordingJit jit = new RecordingJit(/* active= */ true);
    Preloader p = new Preloader(getClass().getClassLoader(), jit);
    p.applyCompile(
        Collections.singletonList(
            new Profile.CompileEntry(
                Fixture.class.getName(), "overloaded", "(Ljava/lang/String;)I", 4)));

    assertThat(jit.enqueued).hasSize(1);
    assertThat(jit.enqueued.get(0).method.getParameterTypes()[0]).isEqualTo(String.class);
  }

  /** Fixture class used by the tests; deliberately does nothing observable. */
  static final class Fixture {
    static void hotMethod() {}

    static int overloaded(String s) {
      return s.length();
    }

    static int overloaded(int x) {
      return x;
    }
  }

  private static final class RecordingJit implements JitEnqueuer {
    final List<Call> enqueued = new ArrayList<>();
    final boolean active;

    RecordingJit() {
      this(true);
    }

    RecordingJit(boolean active) {
      this.active = active;
    }

    @Override
    public void enqueue(Method method, int tier) {
      enqueued.add(new Call(method, tier));
    }

    @Override
    public boolean isActive() {
      return active;
    }
  }

  private static final class Call {
    final Method method;
    final int tier;

    Call(Method method, int tier) {
      this.method = method;
      this.tier = tier;
    }
  }
}
