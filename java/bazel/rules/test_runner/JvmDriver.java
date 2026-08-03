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

import com.google.devtools.build.java.testrunner.extension.Extension;
import com.google.devtools.build.java.testrunner.extension.JvmContext;
import com.google.devtools.build.java.testrunner.extension.RunnerContext;
import com.google.devtools.build.java.testrunner.extension.Store;
import com.google.devtools.build.java.testrunner.extension.TestContext;
import com.google.devtools.build.java.testrunner.wire.Message;
import com.google.devtools.build.java.testrunner.wire.SessionReady;
import com.google.devtools.build.java.testrunner.wire.SessionStart;
import com.google.devtools.build.java.testrunner.wire.StoreGetRequest;
import com.google.devtools.build.java.testrunner.wire.StoreGetResponse;
import com.google.devtools.build.java.testrunner.wire.StoreSetAck;
import com.google.devtools.build.java.testrunner.wire.StoreSetRequest;
import com.google.devtools.build.java.testrunner.wire.TestFinished;
import com.google.devtools.build.java.testrunner.wire.WireChannel;
import com.google.devtools.build.java.testrunner.wire.WorkerShutdown;
import com.google.devtools.build.java.testrunner.wire.WorkerStart;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class JvmDriver {

  private static final String TEST_MAIN_PROP = "bazel.persistent.test_main";
  private static final String EXTENSIONS_FILE_PROP = "bazel.persistent.extensions_file";
  private static final String IPC_C2J_ENV = "BAZEL_PERSISTENT_IPC_C2J";
  private static final String IPC_J2C_ENV = "BAZEL_PERSISTENT_IPC_J2C";

  private JvmDriver() {}

  public static void main(String[] args) throws Exception {
    String testMain = System.getProperty(TEST_MAIN_PROP);
    String c2jPath = System.getenv(IPC_C2J_ENV);
    String j2cPath = System.getenv(IPC_J2C_ENV);
    if (testMain == null || c2jPath == null || j2cPath == null) {
      throw new IllegalStateException(
          "JvmDriver requires -D"
              + TEST_MAIN_PROP
              + " and env "
              + IPC_C2J_ENV
              + "/"
              + IPC_J2C_ENV);
    }

    // Order matters: open read side first (blocks until coordinator opens the matching write
    // side), then the write side (blocks until coordinator opens the matching read side).
    // Mirror of the coordinator's ordering. Explicit locals to avoid Java's arg-evaluation-
    // order pitfall.
    java.io.InputStream childRead = Files.newInputStream(Paths.get(c2jPath));
    java.io.OutputStream childWrite = Files.newOutputStream(Paths.get(j2cPath));
    WireChannel channel = new WireChannel(childRead, childWrite);

    Message hello = channel.read();
    if (!(hello instanceof SessionStart)) {
      throw new IOException("expected SessionStart, got " + hello);
    }
    channel.send(SessionReady.INSTANCE);

    Message workerStart = channel.read();
    if (!(workerStart instanceof WorkerStart)) {
      throw new IOException("expected WorkerStart, got " + workerStart);
    }

    List<Extension> extensions = loadExtensions(System.getProperty(EXTENSIONS_FILE_PROP));
    WireBackedStore store = new WireBackedStore(channel);
    RunnerContext runnerCtx = () -> store;
    JvmContext jvmCtx = () -> store;
    TestContext testCtx = () -> store;

    CompletableFuture<Void> workerShutdownReceived = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                while (true) {
                  Message msg = channel.read();
                  if (msg == null) {
                    workerShutdownReceived.complete(null);
                    return;
                  }
                  if (msg instanceof WorkerShutdown) {
                    workerShutdownReceived.complete(null);
                    return;
                  }
                  store.dispatch(msg);
                }
              } catch (IOException e) {
                // Coordinator closed the wire or sent garbage. Log to stderr; blocking store
                // calls will surface an error via their pending futures.
                e.printStackTrace();
                workerShutdownReceived.complete(null);
              }
            },
            "jvm-driver-wire-reader");
    reader.setDaemon(true);
    reader.start();

    AtomicBoolean shutdownDone = new AtomicBoolean(false);
    Runnable runShutdownHooks =
        () -> {
          if (shutdownDone.compareAndSet(false, true)) {
            invokeShutdown(extensions, ext -> ext.onTestShutdown(testCtx));
            invokeShutdown(extensions, ext -> ext.onJvmShutdown(jvmCtx));
            invokeShutdown(extensions, ext -> ext.onWorkerShutdown(runnerCtx));
          }
        };
    Runtime.getRuntime().addShutdownHook(new Thread(runShutdownHooks, "jvm-driver-shutdown"));

    invokeStart(extensions, ext -> ext.onWorkerStart(runnerCtx));
    invokeStart(extensions, ext -> ext.onJvmStart(jvmCtx));
    invokeStart(extensions, ext -> ext.onTestStart(testCtx));

    int exitCode;
    try {
      Method main = Class.forName(testMain).getMethod("main", String[].class);
      main.invoke(null, (Object) args);
      exitCode = 0;
    } catch (InvocationTargetException e) {
      // If the test called System.exit, control never returns here — the shutdown hook fires
      // the child-side shutdown extension hooks; the coordinator sees EOF on the wire and
      // reads the process's actual exit code.
      Throwable cause = e.getCause();
      (cause != null ? cause : e).printStackTrace();
      exitCode = 1;
    } catch (Throwable t) {
      t.printStackTrace();
      exitCode = 1;
    }

    channel.send(new TestFinished(exitCode));
    System.out.flush();
    System.err.flush();

    // Wait for the coordinator's WorkerShutdown before firing the shutdown hooks.
    workerShutdownReceived.join();
    runShutdownHooks.run();
  }

  private static List<Extension> loadExtensions(String extensionsFile) throws IOException {
    if (extensionsFile == null || extensionsFile.isEmpty()) {
      return Collections.emptyList();
    }
    Path p = Paths.get(extensionsFile);
    if (!Files.exists(p)) {
      return Collections.emptyList();
    }
    List<Extension> extensions = new ArrayList<>();
    for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
      String className = line.trim();
      if (className.isEmpty()) {
        continue;
      }
      try {
        Class<?> cls = Class.forName(className);
        Object instance = cls.getConstructor().newInstance();
        if (!(instance instanceof Extension)) {
          throw new IllegalStateException(className + " does not implement Extension");
        }
        extensions.add((Extension) instance);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("failed to load extension " + className, e);
      }
    }
    // Ascending priority for start hooks; shutdown iteration will reverse.
    extensions.sort(Comparator.comparingInt(Extension::priority));
    return extensions;
  }

  private static void invokeStart(List<Extension> extensions, HookInvocation hook) {
    for (Extension ext : extensions) {
      hook.invoke(ext);
    }
  }

  private static void invokeShutdown(List<Extension> extensions, HookInvocation hook) {
    for (int i = extensions.size() - 1; i >= 0; i--) {
      hook.invoke(extensions.get(i));
    }
  }

  private interface HookInvocation {
    void invoke(Extension ext);
  }

  private static final class WireBackedStore implements Store {
    private final WireChannel channel;
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final ConcurrentMap<Integer, CompletableFuture<Optional<String>>> pendingGets =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompletableFuture<Void>> pendingSets =
        new ConcurrentHashMap<>();

    WireBackedStore(WireChannel channel) {
      this.channel = channel;
    }

    @Override
    public Optional<String> get(String key) {
      int id = nextRequestId.getAndIncrement();
      CompletableFuture<Optional<String>> f = new CompletableFuture<>();
      pendingGets.put(id, f);
      try {
        channel.send(new StoreGetRequest(id, key));
        return f.join();
      } catch (IOException e) {
        pendingGets.remove(id);
        throw new RuntimeException("Store.get failed", e);
      }
    }

    @Override
    public void set(String key, String value) {
      int id = nextRequestId.getAndIncrement();
      CompletableFuture<Void> f = new CompletableFuture<>();
      pendingSets.put(id, f);
      try {
        channel.send(new StoreSetRequest(id, key, value));
        f.join();
      } catch (IOException e) {
        pendingSets.remove(id);
        throw new RuntimeException("Store.set failed", e);
      }
    }

    void dispatch(Message msg) {
      if (msg instanceof StoreGetResponse) {
        StoreGetResponse r = (StoreGetResponse) msg;
        CompletableFuture<Optional<String>> f = pendingGets.remove(r.requestId());
        if (f != null) {
          f.complete(r.present() ? Optional.of(r.value()) : Optional.empty());
        }
      } else if (msg instanceof StoreSetAck) {
        StoreSetAck r = (StoreSetAck) msg;
        CompletableFuture<Void> f = pendingSets.remove(r.requestId());
        if (f != null) {
          f.complete(null);
        }
      }
    }
  }
}
