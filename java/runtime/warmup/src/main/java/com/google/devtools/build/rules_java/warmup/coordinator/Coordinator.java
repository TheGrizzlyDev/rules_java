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
import com.google.devtools.build.rules_java.warmup.common.wire.SnapshotRequest;
import com.google.devtools.build.rules_java.warmup.common.wire.TelemetrySample;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Warmup coordinator: listens on a TCP loopback port, accepts connections from warming and running
 * JVMs, and speaks the wire protocol defined in {@code common.wire}.
 */
public final class Coordinator {

  private static final Logger logger = Logger.getLogger(Coordinator.class.getName());

  private final int port;
  private final int engineCount;
  private final String engineRlocation;
  private final AtomicInteger connectionCounter = new AtomicInteger();

  public Coordinator(int port, int engineCount, String engineRlocation) {
    this.port = port;
    this.engineCount = engineCount;
    this.engineRlocation = engineRlocation;
  }

  public void run() throws IOException {
    Runfiles runfiles = Runfiles.create();
    InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    try (ServerSocket server = new ServerSocket()) {
      server.bind(address);
      int boundPort = server.getLocalPort();
      logger.info("coordinator listening on " + server.getLocalSocketAddress());

      EngineLauncher launcher = new EngineLauncher(runfiles, engineRlocation, boundPort);
      for (int i = 0; i < engineCount; i++) {
        launcher.spawn(i);
      }

      while (true) {
        Socket client = server.accept();
        int id = connectionCounter.incrementAndGet();
        Thread t = new Thread(() -> handle(id, client), "warmup-conn-" + id);
        t.setDaemon(true);
        t.start();
      }
    }
  }

  private void handle(int id, Socket client) {
    logger.info("connection " + id + " opened");
    try (Socket c = client;
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream()) {
      while (true) {
        Object message;
        try {
          message = Codec.read(in);
        } catch (EOFException e) {
          logger.info("connection " + id + " closed by peer");
          return;
        }
        onMessage(id, message, out);
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "connection " + id + " error", e);
    }
  }

  private void onMessage(int id, Object message, OutputStream out) throws IOException {
    if (message instanceof SnapshotRequest) {
      logger.info("connection " + id + " -> snapshot request");
      Codec.writeProfile(out, emptyProfile());
    } else if (message instanceof TelemetrySample) {
      TelemetrySample sample = (TelemetrySample) message;
      logger.info(
          "connection "
              + id
              + " -> telemetry: "
              + sample.loadedClasses().size()
              + " classes, "
              + sample.hotMethods().size()
              + " hot methods");
    } else {
      logger.warning("connection " + id + " -> unexpected " + message.getClass().getSimpleName());
    }
  }

  private static Profile emptyProfile() {
    return new Profile(
        Collections.<Profile.PreloadEntry>emptyList(),
        Collections.<Profile.CompileEntry>emptyList());
  }

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
    new Coordinator(port, engineCount, engineRlocation).run();
  }
}
