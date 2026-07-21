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
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import com.google.devtools.build.rules_java.warmup.common.wire.Codec;
import com.google.devtools.build.rules_java.warmup.common.wire.PromoteRequest;
import com.google.devtools.build.rules_java.warmup.common.wire.SnapshotRequest;
import com.google.devtools.build.rules_java.warmup.common.wire.StateStaleNotice;
import com.google.devtools.build.rules_java.warmup.common.wire.TelemetrySample;
import com.google.devtools.build.rules_java.warmup.common.wire.WarmupUpdate;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests: run a real Coordinator in-JVM and drive it with a wire-protocol client. */
@RunWith(JUnit4.class)
public final class CoordinatorTest {

  private Coordinator coordinator;
  private int port;
  private Thread serverThread;

  @Before
  public void setUp() throws Exception {
    coordinator = new Coordinator(0);
    port = coordinator.bind();
    serverThread =
        new Thread(
            () -> {
              try {
                coordinator.serve();
              } catch (IOException e) {
                // Expected when stop() closes the listen socket.
              }
            },
            "coordinator-serve");
    serverThread.setDaemon(true);
    serverThread.start();
  }

  @After
  public void tearDown() throws Exception {
    coordinator.stop();
    serverThread.join(2000);
  }

  @Test
  public void snapshotRequestGetsProfileReply() throws Exception {
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      Object reply = client.read();
      assertThat(reply).isInstanceOf(Profile.class);
    }
  }

  @Test
  public void sessionMovesToWarmingAfterSnapshotReply() throws Exception {
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      Object reply = client.read();
      assertThat(reply).isInstanceOf(Profile.class);
      Session session = awaitOneSession(Session.State.WARMING);
      assertThat(session.state()).isEqualTo(Session.State.WARMING);
    }
  }

  @Test
  public void promoteMovesSessionToPromotedAndClientReceivesEvent() throws Exception {
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      assertThat(client.read()).isInstanceOf(Profile.class);

      Session session = awaitOneSession(Session.State.WARMING);
      coordinator.promote(session.id(), "test ready");

      Object event = client.read();
      assertThat(event).isInstanceOf(PromoteRequest.class);
      assertThat(((PromoteRequest) event).reason()).isEqualTo("test ready");
      assertThat(session.state()).isEqualTo(Session.State.PROMOTED);
    }
  }

  @Test
  public void warmupUpdateDeliversToClient() throws Exception {
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      assertThat(client.read()).isInstanceOf(Profile.class);

      Session session = awaitOneSession(Session.State.WARMING);
      WarmupUpdate update =
          new WarmupUpdate(
              Collections.singletonList(new Profile.PreloadEntry("com.example.Foo", "deadbeef")),
              Collections.emptyList());
      coordinator.sendWarmupUpdate(session.id(), update);

      Object event = client.read();
      assertThat(event).isInstanceOf(WarmupUpdate.class);
      assertThat(((WarmupUpdate) event).preload().get(0).fqClassName()).isEqualTo("com.example.Foo");
    }
  }

  @Test
  public void staleNoticeDeliversToClient() throws Exception {
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      assertThat(client.read()).isInstanceOf(Profile.class);

      Session session = awaitOneSession(Session.State.WARMING);
      coordinator.sendStale(
          session.id(),
          new StateStaleNotice("com.example.Foo", "deadbeef", "feedface", "digest mismatch"));

      Object event = client.read();
      assertThat(event).isInstanceOf(StateStaleNotice.class);
      assertThat(((StateStaleNotice) event).fqClassName()).isEqualTo("com.example.Foo");
    }
  }

  @Test
  public void telemetryFromClientIsAcceptedInAnyState() throws Exception {
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      assertThat(client.read()).isInstanceOf(Profile.class);

      TelemetrySample sample =
          new TelemetrySample(
              Collections.singletonList(new Profile.PreloadEntry("com.example.Foo", "deadbeef")),
              Collections.emptyList());
      client.sendTelemetry(sample);
      // No exception, no reply — coordinator just logs it. Give the reader a beat to consume.
      Thread.sleep(200);
      assertThat(awaitOneSession(Session.State.WARMING)).isNotNull();
    }
  }

  @Test
  public void promoteBeforeSnapshotIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> coordinator.promote(999, "no such session"));
  }

  @Test
  public void promoteFromConnectedStateIsRejected() throws Exception {
    try (Client client = Client.connect(port)) {
      Session session = awaitOneSession(Session.State.CONNECTED);
      assertThrows(
          IllegalStateException.class, () -> coordinator.promote(session.id(), "too early"));
    }
  }

  @Test
  public void closingClientRemovesSession() throws Exception {
    int sessionId;
    try (Client client = Client.connect(port)) {
      client.sendSnapshot();
      assertThat(client.read()).isInstanceOf(Profile.class);
      Session session = awaitOneSession(Session.State.WARMING);
      sessionId = session.id();
    }
    long deadline = System.currentTimeMillis() + 2000;
    while (coordinator.sessions().stream().anyMatch(s -> s.id() == sessionId)) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("session " + sessionId + " not removed after client close");
      }
      Thread.sleep(20);
    }
  }

  private Session awaitOneSession(Session.State expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline) {
      for (Session s : coordinator.sessions()) {
        if (s.state() == expected) {
          return s;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no session reached state " + expected + " within timeout");
  }

  /** Minimal wire-protocol client that speaks Codec directly. */
  private static final class Client implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private Client(Socket socket) throws IOException {
      this.socket = socket;
      this.in = socket.getInputStream();
      this.out = socket.getOutputStream();
    }

    static Client connect(int port) throws IOException {
      return new Client(new Socket(InetAddress.getLoopbackAddress(), port));
    }

    void sendSnapshot() throws IOException {
      Codec.writeSnapshotRequest(out, SnapshotRequest.INSTANCE);
    }

    void sendTelemetry(TelemetrySample sample) throws IOException {
      Codec.writeTelemetrySample(out, sample);
    }

    Object read() throws IOException {
      return Codec.read(in);
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
