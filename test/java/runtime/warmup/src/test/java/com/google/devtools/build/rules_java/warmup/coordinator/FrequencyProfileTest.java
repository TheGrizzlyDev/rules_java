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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import com.google.devtools.build.rules_java.warmup.common.wire.TelemetrySample;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FrequencyProfile}. */
@RunWith(JUnit4.class)
public final class FrequencyProfileTest {

  private static Profile.PreloadEntry preload(String name, String digest) {
    return new Profile.PreloadEntry(name, digest);
  }

  private static Profile.CompileEntry compile(String cls, String method, String descriptor) {
    return new Profile.CompileEntry(cls, method, descriptor, 4);
  }

  @Test
  public void emptyProfileSnapshotsEmpty() {
    FrequencyProfile fp = new FrequencyProfile();
    Profile snapshot = fp.snapshot(10, 10, 1);
    assertThat(snapshot.preload()).isEmpty();
    assertThat(snapshot.compile()).isEmpty();
  }

  @Test
  public void snapshotRanksByFrequency() {
    FrequencyProfile fp = new FrequencyProfile();
    // com.example.Rare appears once
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Rare", "digestR")), Collections.emptyList()));
    // com.example.Common appears three times
    for (int i = 0; i < 3; i++) {
      fp.merge(
          new TelemetrySample(
              Arrays.asList(preload("com.example.Common", "digestC")), Collections.emptyList()));
    }

    Profile snapshot = fp.snapshot(10, 10, 1);
    List<String> names =
        snapshot.preload().stream().map(Profile.PreloadEntry::fqClassName).collect(Collectors.toList());
    assertThat(names).containsExactly("com.example.Common", "com.example.Rare").inOrder();
  }

  @Test
  public void snapshotRespectsMinCount() {
    FrequencyProfile fp = new FrequencyProfile();
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Once", "d1")), Collections.emptyList()));
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Twice", "d2")), Collections.emptyList()));
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Twice", "d2")), Collections.emptyList()));

    Profile snapshot = fp.snapshot(10, 10, /* minCount= */ 2);
    assertThat(snapshot.preload()).hasSize(1);
    assertThat(snapshot.preload().get(0).fqClassName()).isEqualTo("com.example.Twice");
  }

  @Test
  public void snapshotRespectsTopN() {
    FrequencyProfile fp = new FrequencyProfile();
    for (int i = 0; i < 5; i++) {
      fp.merge(
          new TelemetrySample(
              Arrays.asList(preload("com.example.C" + i, "d" + i)), Collections.emptyList()));
    }

    Profile snapshot = fp.snapshot(/* topClasses= */ 3, 10, 1);
    assertThat(snapshot.preload()).hasSize(3);
  }

  @Test
  public void snapshotIsDeterministicUnderTies() {
    FrequencyProfile fp = new FrequencyProfile();
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.B", "dB"), preload("com.example.A", "dA")),
            Collections.emptyList()));

    Profile snapshot = fp.snapshot(10, 10, 1);
    List<String> names =
        snapshot.preload().stream().map(Profile.PreloadEntry::fqClassName).collect(Collectors.toList());
    assertThat(names).containsExactly("com.example.A", "com.example.B").inOrder();
  }

  @Test
  public void snapshotCarriesLastSeenDigest() {
    FrequencyProfile fp = new FrequencyProfile();
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Foo", "old")), Collections.emptyList()));
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Foo", "new")), Collections.emptyList()));

    Profile snapshot = fp.snapshot(10, 10, 1);
    assertThat(snapshot.preload().get(0).sha256DigestHex()).isEqualTo("new");
    assertThat(fp.lastDigest("com.example.Foo")).isEqualTo("new");
  }

  @Test
  public void emptyDigestDoesNotOverwriteKnownDigest() {
    FrequencyProfile fp = new FrequencyProfile();
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Foo", "known")), Collections.emptyList()));
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Foo", "")), Collections.emptyList()));

    assertThat(fp.lastDigest("com.example.Foo")).isEqualTo("known");
  }

  @Test
  public void hotMethodsRankByFrequency() {
    FrequencyProfile fp = new FrequencyProfile();
    fp.merge(
        new TelemetrySample(
            Collections.emptyList(), Arrays.asList(compile("com.example.C", "rare", "()V"))));
    for (int i = 0; i < 3; i++) {
      fp.merge(
          new TelemetrySample(
              Collections.emptyList(), Arrays.asList(compile("com.example.C", "common", "()V"))));
    }

    Profile snapshot = fp.snapshot(10, 10, 1);
    List<String> methodNames =
        snapshot.compile().stream().map(Profile.CompileEntry::methodName).collect(Collectors.toList());
    assertThat(methodNames).containsExactly("common", "rare").inOrder();
  }

  @Test
  public void countsAreAdditiveAcrossMerges() {
    FrequencyProfile fp = new FrequencyProfile();
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Foo", "d")), Collections.emptyList()));
    fp.merge(
        new TelemetrySample(
            Arrays.asList(preload("com.example.Foo", "d")), Collections.emptyList()));

    assertThat(fp.classCount()).isEqualTo(1);
    // Both merges counted — the class still shows up with the minimum threshold of 2.
    Profile snapshot = fp.snapshot(10, 10, /* minCount= */ 2);
    assertThat(snapshot.preload()).hasSize(1);
  }
}
