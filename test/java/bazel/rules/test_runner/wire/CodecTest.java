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
package com.google.devtools.build.java.testrunner.wire;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CodecTest {

  @Test
  public void sessionStart_roundTrip() throws IOException {
    assertThat(roundTrip(SessionStart.INSTANCE)).isSameInstanceAs(SessionStart.INSTANCE);
  }

  @Test
  public void sessionReady_roundTrip() throws IOException {
    assertThat(roundTrip(SessionReady.INSTANCE)).isSameInstanceAs(SessionReady.INSTANCE);
  }

  @Test
  public void storeGetRequest_roundTrip() throws IOException {
    StoreGetRequest original = new StoreGetRequest(42, "some.key");
    StoreGetRequest decoded = (StoreGetRequest) roundTrip(original);
    assertThat(decoded.requestId()).isEqualTo(42);
    assertThat(decoded.key()).isEqualTo("some.key");
  }

  @Test
  public void storeGetResponse_present_roundTrip() throws IOException {
    StoreGetResponse decoded = (StoreGetResponse) roundTrip(new StoreGetResponse(7, true, "v"));
    assertThat(decoded.requestId()).isEqualTo(7);
    assertThat(decoded.present()).isTrue();
    assertThat(decoded.value()).isEqualTo("v");
  }

  @Test
  public void storeGetResponse_absent_roundTrip() throws IOException {
    StoreGetResponse decoded = (StoreGetResponse) roundTrip(new StoreGetResponse(8, false, ""));
    assertThat(decoded.requestId()).isEqualTo(8);
    assertThat(decoded.present()).isFalse();
    assertThat(decoded.value()).isEmpty();
  }

  @Test
  public void storeSetRequest_roundTrip() throws IOException {
    StoreSetRequest decoded = (StoreSetRequest) roundTrip(new StoreSetRequest(1, "k", "hello"));
    assertThat(decoded.requestId()).isEqualTo(1);
    assertThat(decoded.key()).isEqualTo("k");
    assertThat(decoded.value()).isEqualTo("hello");
  }

  @Test
  public void storeSetAck_roundTrip() throws IOException {
    StoreSetAck decoded = (StoreSetAck) roundTrip(new StoreSetAck(99));
    assertThat(decoded.requestId()).isEqualTo(99);
  }

  @Test
  public void testFinished_roundTrip() throws IOException {
    TestFinished decoded = (TestFinished) roundTrip(new TestFinished(0));
    assertThat(decoded.exitCode()).isEqualTo(0);
  }

  @Test
  public void testFinished_nonZeroExitCode_roundTrip() throws IOException {
    TestFinished decoded = (TestFinished) roundTrip(new TestFinished(137));
    assertThat(decoded.exitCode()).isEqualTo(137);
  }

  @Test
  public void unicodeStrings_roundTrip() throws IOException {
    StoreSetRequest original = new StoreSetRequest(1, "key/日本語", "value/λ/🙂");
    StoreSetRequest decoded = (StoreSetRequest) roundTrip(original);
    assertThat(decoded.key()).isEqualTo("key/日本語");
    assertThat(decoded.value()).isEqualTo("value/λ/🙂");
  }

  @Test
  public void emptyStrings_roundTrip() throws IOException {
    StoreSetRequest decoded = (StoreSetRequest) roundTrip(new StoreSetRequest(0, "", ""));
    assertThat(decoded.requestId()).isEqualTo(0);
    assertThat(decoded.key()).isEmpty();
    assertThat(decoded.value()).isEmpty();
  }

  @Test
  public void largeVarint_roundTrip() throws IOException {
    StoreGetRequest decoded =
        (StoreGetRequest) roundTrip(new StoreGetRequest(Integer.MAX_VALUE, "k"));
    assertThat(decoded.requestId()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  public void twoMessages_readInSequence() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.write(out, new StoreGetRequest(1, "a"));
    Codec.write(out, new TestFinished(0));

    ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
    Message first = Codec.read(in);
    Message second = Codec.read(in);
    assertThat(first).isInstanceOf(StoreGetRequest.class);
    assertThat(second).isInstanceOf(TestFinished.class);
    assertThat(Codec.read(in)).isNull();
  }

  @Test
  public void read_emptyStream_returnsNull() throws IOException {
    assertThat(Codec.read(new ByteArrayInputStream(new byte[0]))).isNull();
  }

  @Test
  public void read_truncatedPayload_throwsEOF() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      Codec.write(out, new StoreSetRequest(1, "k", "v"));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    byte[] full = out.toByteArray();
    byte[] truncated = new byte[full.length - 2];
    System.arraycopy(full, 0, truncated, 0, truncated.length);
    assertThrows(EOFException.class, () -> Codec.read(new ByteArrayInputStream(truncated)));
  }

  @Test
  public void read_unknownTag_throws() {
    byte[] bytes = {(byte) 250, 0}; // unknown tag, zero payload length.
    assertThrows(IllegalArgumentException.class, () -> Codec.read(new ByteArrayInputStream(bytes)));
  }

  private static Message roundTrip(Message msg) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Codec.write(out, msg);
    Message decoded = Codec.read(new ByteArrayInputStream(out.toByteArray()));
    assertThat(Codec.read(new ByteArrayInputStream(out.toByteArray()))).isNotNull();
    return decoded;
  }
}
