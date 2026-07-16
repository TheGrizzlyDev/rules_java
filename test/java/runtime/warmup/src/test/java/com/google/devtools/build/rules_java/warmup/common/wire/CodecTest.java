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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Codec}. */
@RunWith(JUnit4.class)
public final class CodecTest {

  private static final List<Profile.PreloadEntry> PRELOAD =
      Arrays.asList(
          new Profile.PreloadEntry("com.example.Foo", "deadbeef"),
          new Profile.PreloadEntry("com.example.Bar", "cafebabe"));

  private static final List<Profile.CompileEntry> COMPILE =
      Collections.singletonList(
          new Profile.CompileEntry("com.example.Foo", "doWork", "(Ljava/lang/String;)I", 4));

  @Test
  public void roundTripsWarmupUpdate() throws IOException {
    WarmupUpdate original = new WarmupUpdate(PRELOAD, COMPILE);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeServerEvent(out, original);

    Object decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(decoded).isInstanceOf(WarmupUpdate.class);
    WarmupUpdate u = (WarmupUpdate) decoded;
    assertThat(u.preload()).hasSize(2);
    assertThat(u.preload().get(0).fqClassName()).isEqualTo("com.example.Foo");
    assertThat(u.preload().get(0).sha256DigestHex()).isEqualTo("deadbeef");
    assertThat(u.compile()).hasSize(1);
    assertThat(u.compile().get(0).methodName()).isEqualTo("doWork");
    assertThat(u.compile().get(0).descriptor()).isEqualTo("(Ljava/lang/String;)I");
    assertThat(u.compile().get(0).tier()).isEqualTo(4);
  }

  @Test
  public void roundTripsStateStaleNotice() throws IOException {
    StateStaleNotice original =
        new StateStaleNotice("com.example.Foo", "deadbeef", "feedface", "digest mismatch");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeServerEvent(out, original);

    Object decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(decoded).isInstanceOf(StateStaleNotice.class);
    StateStaleNotice s = (StateStaleNotice) decoded;
    assertThat(s.fqClassName()).isEqualTo("com.example.Foo");
    assertThat(s.warmedDigestHex()).isEqualTo("deadbeef");
    assertThat(s.reportedDigestHex()).isEqualTo("feedface");
    assertThat(s.reason()).isEqualTo("digest mismatch");
  }

  @Test
  public void roundTripsPromoteRequest() throws IOException {
    PromoteRequest original = new PromoteRequest("warmer-1", "test ready");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeServerEvent(out, original);

    Object decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(decoded).isInstanceOf(PromoteRequest.class);
    PromoteRequest p = (PromoteRequest) decoded;
    assertThat(p.warmingJvmId()).isEqualTo("warmer-1");
    assertThat(p.reason()).isEqualTo("test ready");
  }

  @Test
  public void roundTripsTelemetrySample() throws IOException {
    TelemetrySample original = new TelemetrySample(PRELOAD, COMPILE);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeTelemetrySample(out, original);

    Object decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(decoded).isInstanceOf(TelemetrySample.class);
    TelemetrySample s = (TelemetrySample) decoded;
    assertThat(s.loadedClasses()).hasSize(2);
    assertThat(s.hotMethods()).hasSize(1);
  }

  @Test
  public void roundTripsSnapshotRequest() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeSnapshotRequest(out, SnapshotRequest.INSTANCE);

    Object decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(decoded).isSameInstanceAs(SnapshotRequest.INSTANCE);
  }

  @Test
  public void roundTripsProfile() throws IOException {
    Profile original = new Profile(PRELOAD, COMPILE);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeProfile(out, original);

    Object decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(decoded).isInstanceOf(Profile.class);
    Profile p = (Profile) decoded;
    assertThat(p.preload()).hasSize(2);
    assertThat(p.compile()).hasSize(1);
  }

  @Test
  public void readsMultipleMessagesFromOneStream() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeServerEvent(out, new WarmupUpdate(PRELOAD, Collections.emptyList()));
    Codec.writeServerEvent(out, new PromoteRequest("warmer-1", "go"));

    InputStream in = new ByteArrayInputStream(out.toByteArray());
    assertThat(Codec.read(in)).isInstanceOf(WarmupUpdate.class);
    assertThat(Codec.read(in)).isInstanceOf(PromoteRequest.class);
  }

  @Test
  public void rejectsUnknownTag() {
    byte[] bytes = {(byte) 99, 0};
    assertThrows(
        IllegalArgumentException.class, () -> Codec.read(new ByteArrayInputStream(bytes)));
  }

  @Test
  public void rejectsTruncatedFrame() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.writeServerEvent(out, new PromoteRequest("warmer-1", "go"));
    byte[] bytes = out.toByteArray();
    byte[] truncated = Arrays.copyOf(bytes, bytes.length - 3);
    assertThrows(EOFException.class, () -> Codec.read(new ByteArrayInputStream(truncated)));
  }

  @Test
  public void rejectsEmptyStream() {
    assertThrows(EOFException.class, () -> Codec.read(new ByteArrayInputStream(new byte[0])));
  }

  @Test
  public void rejectsOversizePayload() {
    int hugeLen = 64 * 1024 * 1024;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(Codec.Tag.SERVER_EVENT_PROMOTE.code());
    writeVarint(out, hugeLen);
    assertThrows(IOException.class, () -> Codec.read(new ByteArrayInputStream(out.toByteArray())));
  }

  private static void writeVarint(ByteArrayOutputStream out, int value) {
    int v = value;
    while ((v & ~0x7F) != 0) {
      out.write((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out.write(v & 0x7F);
  }
}
