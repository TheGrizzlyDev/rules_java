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
import com.google.devtools.build.rules_java.warmup.common.wire.Codec;
import com.google.devtools.build.rules_java.warmup.common.wire.PromoteRequest;
import com.google.devtools.build.rules_java.warmup.common.wire.SnapshotRequest;
import com.google.devtools.build.rules_java.warmup.common.wire.StateStaleNotice;
import com.google.devtools.build.rules_java.warmup.common.wire.TelemetrySample;
import com.google.devtools.build.rules_java.warmup.common.wire.WarmupUpdate;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Warmup coordinator: listens on a TCP loopback port, accepts connections from warming and running
 * JVMs, and speaks the wire protocol defined in {@code common.wire}. Provides an admin API for
 * pushing events at specific engines.
 */
public final class Coordinator {

  private static final Logger logger = Logger.getLogger(Coordinator.class.getName());

  private final int port;
  private final SessionRegistry sessions = new SessionRegistry();
  private final AtomicInteger connectionCounter = new AtomicInteger();
  private volatile ServerSocket server;
  private volatile Thread acceptThread;

  public Coordinator(int port) {
    this.port = port;
  }

  /** Binds the listen socket and returns the bound port. Non-blocking; call {@link #serve()}. */
  public int bind() throws IOException {
    InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    ServerSocket s = new ServerSocket();
    s.bind(address);
    server = s;
    logger.info("coordinator listening on " + s.getLocalSocketAddress());
    return s.getLocalPort();
  }

  /** Blocks accepting connections until {@link #stop()} is called. */
  public void serve() throws IOException {
    if (server == null) {
      throw new IllegalStateException("bind() must be called before serve()");
    }
    acceptThread = Thread.currentThread();
    try {
      while (!server.isClosed()) {
        Socket client;
        try {
          client = server.accept();
        } catch (IOException e) {
          if (server.isClosed()) {
            return;
          }
          throw e;
        }
        int id = connectionCounter.incrementAndGet();
        Session session;
        try {
          session = new Session(id, client);
        } catch (IOException e) {
          logger.log(Level.WARNING, "connection " + id + " setup failed", e);
          try {
            client.close();
          } catch (IOException ignored) {
            // best effort
          }
          continue;
        }
        sessions.register(session);
        Thread t = new Thread(() -> readLoop(session), "warmup-conn-" + id);
        t.setDaemon(true);
        t.start();
      }
    } finally {
      acceptThread = null;
    }
  }

  /** Closes the listen socket and every live session. */
  public void stop() {
    ServerSocket s = server;
    if (s != null) {
      try {
        s.close();
      } catch (IOException ignored) {
        // best effort
      }
    }
    for (Session session : sessions.all()) {
      session.close();
    }
  }

  // ---- admin API ----

  public Collection<Session> sessions() {
    return sessions.all();
  }

  public Optional<Session> pickWarming() {
    return sessions.pickWarming();
  }

  public void promote(int sessionId, String reason) throws IOException {
    Session session = requireSession(sessionId);
    session.sendPromoteRequest(new PromoteRequest("warmer-" + sessionId, reason));
  }

  public void sendWarmupUpdate(int sessionId, WarmupUpdate update) throws IOException {
    requireSession(sessionId).sendWarmupUpdate(update);
  }

  public void sendStale(int sessionId, StateStaleNotice notice) throws IOException {
    requireSession(sessionId).sendStaleNotice(notice);
  }

  private Session requireSession(int id) {
    Session s = sessions.get(id);
    if (s == null) {
      throw new IllegalArgumentException("no such session: " + id);
    }
    return s;
  }

  // ---- per-connection reader ----

  private void readLoop(Session session) {
    logger.info("connection " + session.id() + " opened");
    try {
      while (true) {
        Object message;
        try {
          message = Codec.read(session.input());
        } catch (EOFException e) {
          logger.info("connection " + session.id() + " closed by peer");
          return;
        }
        onMessage(session, message);
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "connection " + session.id() + " error", e);
    } finally {
      session.close();
      sessions.remove(session.id());
    }
  }

  private void onMessage(Session session, Object message) throws IOException {
    if (message instanceof SnapshotRequest) {
      logger.info("connection " + session.id() + " -> snapshot request");
      session.sendSnapshotReply(emptyProfile());
    } else if (message instanceof TelemetrySample) {
      TelemetrySample sample = (TelemetrySample) message;
      logger.info(
          "connection "
              + session.id()
              + " -> telemetry: "
              + sample.loadedClasses().size()
              + " classes, "
              + sample.hotMethods().size()
              + " hot methods");
    } else {
      logger.warning(
          "connection " + session.id() + " -> unexpected " + message.getClass().getSimpleName());
    }
  }

  private static Profile emptyProfile() {
    return new Profile(
        Collections.<Profile.PreloadEntry>emptyList(),
        Collections.<Profile.CompileEntry>emptyList());
  }

  // ---- CLI ----

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("usage: Coordinator <port> <engine-count> <engine-rlocation>");
      System.err.println("  port=0 for an ephemeral port");
      System.err.println(
          "  engine-rlocation is a runfiles-relative path, e.g. _main/java/.../engine/engine");
      System.exit(2);
    }
    int port = Integer.parseInt(args[0]);
    int engineCount = Integer.parseInt(args[1]);
    String engineRlocation = args[2];

    Runfiles runfiles = Runfiles.create();
    Coordinator coordinator = new Coordinator(port);
    int boundPort = coordinator.bind();

    EngineLauncher launcher = new EngineLauncher(runfiles, engineRlocation, boundPort);
    for (int i = 0; i < engineCount; i++) {
      launcher.spawn(i);
    }

    coordinator.serve();
  }
}
