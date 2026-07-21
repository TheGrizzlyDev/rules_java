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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;
import jdk.jfr.consumer.RecordingStream;

/**
 * Warming-JVM engine: connects to the coordinator on TCP loopback, requests a snapshot, then reads
 * {@link com.google.devtools.build.rules_java.warmup.common.wire.ServerEvent}s until promoted.
 *
 * <p>Telemetry reporting only starts <em>after</em> promotion. Class loads and execution samples
 * observed during the warming phase are noise (the engine's own scaffolding); the signal we want
 * for the profile is what happens once the test starts using the JVM.
 */
public final class Engine {

  private static final Logger logger = Logger.getLogger(Engine.class.getName());

  /** How often the background reporter drains its counters and emits a TelemetrySample. */
  private static final Duration REPORTING_INTERVAL = Duration.ofSeconds(5);

  /** Advisory JIT tier reported for every hot method. JFR doesn't tell us the real tier. */
  private static final int ADVISORY_TIER = 4;

  private final int port;
  private final Object writeLock = new Object();

  public Engine(int port) {
    this.port = port;
  }

  public void run() throws IOException {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream()) {
      logger.info("engine connected to " + socket.getRemoteSocketAddress());

      synchronized (writeLock) {
        Codec.writeSnapshotRequest(out, SnapshotRequest.INSTANCE);
      }
      Object reply = Codec.read(in);
      if (!(reply instanceof Profile)) {
        throw new IOException("expected Profile, got " + reply.getClass().getSimpleName());
      }
      Profile profile = (Profile) reply;
      logger.info(
          "snapshot received: "
              + profile.preload().size()
              + " preload, "
              + profile.compile().size()
              + " compile");

      readWarmupEvents(in);

      // Promotion happened. Start telemetry now — everything observed from this point on is what
      // the test is actually driving.
      Thread reporter = startTelemetryReporter(out);
      awaitTestExit(in, reporter);
    }
  }

  /** Reads warmup-phase events until a {@link PromoteRequest} arrives. */
  private void readWarmupEvents(InputStream in) throws IOException {
    while (true) {
      Object event = Codec.read(in);
      if (event instanceof WarmupUpdate) {
        WarmupUpdate u = (WarmupUpdate) event;
        logger.info(
            "warmup update: +"
                + u.preload().size()
                + " preload, +"
                + u.compile().size()
                + " compile");
      } else if (event instanceof StateStaleNotice) {
        StateStaleNotice n = (StateStaleNotice) event;
        logger.info("stale: " + n.fqClassName() + " (" + n.reason() + ")");
      } else if (event instanceof PromoteRequest) {
        PromoteRequest p = (PromoteRequest) event;
        logger.info("promoted (" + p.reason() + ")");
        return;
      } else {
        logger.warning("unexpected event: " + event.getClass().getSimpleName());
      }
    }
  }

  /**
   * After promotion, keeps the socket alive so the telemetry reporter can push samples. Returns
   * when the coordinator closes the connection or the read stream fails; the reporter is
   * interrupted before we leave.
   *
   * <p>TODO(M5): replace this with the actual test-runner handoff. For now the engine just idles
   * post-promotion so telemetry keeps flowing.
   */
  private void awaitTestExit(InputStream in, Thread reporter) {
    try {
      while (in.read() >= 0) {
        // Coordinator isn't expected to send more messages after promoting; anything that arrives
        // is drained until the peer closes.
      }
    } catch (IOException e) {
      logger.log(Level.FINE, "post-promotion read ended", e);
    } finally {
      reporter.interrupt();
    }
  }

  private Thread startTelemetryReporter(OutputStream out) {
    Thread t = new Thread(() -> runTelemetryReporter(out), "engine-telemetry");
    t.setDaemon(true);
    t.start();
    return t;
  }

  private void runTelemetryReporter(OutputStream out) {
    Map<String, LongAdder> loadedClasses = new ConcurrentHashMap<>();
    Map<MethodKey, LongAdder> hotMethods = new ConcurrentHashMap<>();

    try (RecordingStream rs = new RecordingStream()) {
      rs.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      rs.onEvent(
          "jdk.ExecutionSample",
          event -> {
            var stackTrace = event.getStackTrace();
            if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
              return;
            }
            var method = stackTrace.getFrames().get(0).getMethod();
            MethodKey key =
                new MethodKey(
                    method.getType().getName(), method.getName(), method.getDescriptor());
            hotMethods.computeIfAbsent(key, k -> new LongAdder()).increment();
          });

      rs.enable("jdk.ClassLoad");
      rs.onEvent(
          "jdk.ClassLoad",
          event -> {
            var loadedClass = event.getClass("loadedClass");
            if (loadedClass != null) {
              loadedClasses.computeIfAbsent(loadedClass.getName(), k -> new LongAdder())
                  .increment();
            }
          });

      rs.startAsync();

      while (!Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(REPORTING_INTERVAL.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        emitSample(out, loadedClasses, hotMethods);
      }
    }
  }

  private void emitSample(
      OutputStream out,
      Map<String, LongAdder> loadedClasses,
      Map<MethodKey, LongAdder> hotMethods) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = ClassLoader.getSystemClassLoader();
    }

    List<Profile.PreloadEntry> preload = new ArrayList<>();
    for (Map.Entry<String, LongAdder> e : loadedClasses.entrySet()) {
      String digest;
      try {
        String computed = ClassDigest.forClass(e.getKey(), loader);
        digest = computed == null ? "" : computed;
      } catch (IOException ex) {
        logger.log(Level.FINE, "digest failed for " + e.getKey(), ex);
        digest = "";
      }
      preload.add(new Profile.PreloadEntry(e.getKey(), digest));
    }
    loadedClasses.clear();

    List<Profile.CompileEntry> compile = new ArrayList<>();
    for (Map.Entry<MethodKey, LongAdder> e : hotMethods.entrySet()) {
      MethodKey k = e.getKey();
      compile.add(new Profile.CompileEntry(k.className, k.methodName, k.descriptor, ADVISORY_TIER));
    }
    hotMethods.clear();

    if (preload.isEmpty() && compile.isEmpty()) {
      return;
    }

    TelemetrySample sample = new TelemetrySample(preload, compile);
    try {
      synchronized (writeLock) {
        Codec.writeTelemetrySample(out, sample);
      }
      logger.fine(
          "telemetry sent: " + preload.size() + " classes, " + compile.size() + " hot methods");
    } catch (IOException e) {
      logger.log(Level.WARNING, "failed to send telemetry sample", e);
    }
  }

  private static final class MethodKey {
    final String className;
    final String methodName;
    final String descriptor;

    MethodKey(String className, String methodName, String descriptor) {
      this.className = className;
      this.methodName = methodName;
      this.descriptor = descriptor;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof MethodKey)) {
        return false;
      }
      MethodKey k = (MethodKey) o;
      return className.equals(k.className)
          && methodName.equals(k.methodName)
          && descriptor.equals(k.descriptor);
    }

    @Override
    public int hashCode() {
      return Objects.hash(className, methodName, descriptor);
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: Engine <port>");
      System.exit(2);
    }
    new Engine(Integer.parseInt(args[0])).run();
  }
}
