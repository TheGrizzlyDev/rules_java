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
import com.google.devtools.build.java.testrunner.extension.TestContext;
import com.google.devtools.build.java.testrunner.wire.Message;
import com.google.devtools.build.java.testrunner.wire.SessionReady;
import com.google.devtools.build.java.testrunner.wire.SessionStart;
import com.google.devtools.build.java.testrunner.wire.TestFinished;
import com.google.devtools.build.java.testrunner.wire.TestOutput;
import com.google.devtools.build.java.testrunner.wire.TestStart;
import com.google.devtools.build.java.testrunner.wire.WireChannel;
import com.google.devtools.build.java.testrunner.wire.WorkerShutdown;
import com.google.devtools.build.java.testrunner.wire.WorkerStart;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Sequential test dispatch into one deliberately warm classloader. */
final class ReusableTestDriver {
  private ReusableTestDriver() {}

  static void run() throws Exception {
    WireChannel channel =
        new WireChannel(
            new java.io.FileInputStream(System.getenv("BAZEL_PERSISTENT_IPC_C2J")),
            new java.io.FileOutputStream(System.getenv("BAZEL_PERSISTENT_IPC_J2C")));
    if (!(channel.read() instanceof SessionStart)) {
      throw new IOException("Expected SessionStart");
    }
    channel.send(SessionReady.INSTANCE);
    if (!(channel.read() instanceof WorkerStart)) {
      throw new IOException("Expected WorkerStart");
    }
    JvmDriver.WireBackedStore store = new JvmDriver.WireBackedStore(channel);
    BlockingQueue<Message> requests = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                while (true) {
                  Message message = channel.read();
                  if (message == null || message instanceof WorkerShutdown) {
                    break;
                  }
                  if (message instanceof TestStart) {
                    requests.add(message);
                  } else {
                    store.dispatch(message);
                  }
                }
                store.fail(new IOException("Coordinator closed the session"));
              } catch (IOException | RuntimeException e) {
                e.printStackTrace();
                store.fail(new IOException("Coordinator session failed", e));
              } finally {
                requests.add(WorkerShutdown.INSTANCE);
              }
            },
            "jvm-driver-wire-reader");
    reader.setDaemon(true);
    reader.start();

    System.setOut(wireOutput(channel, false));
    System.setErr(wireOutput(channel, true));
    WarmClassLoader loader = (WarmClassLoader) ClassLoader.getSystemClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    String bootstrapClasspath = System.getProperty("java.class.path");
    String token = UUID.randomUUID().toString();
    String pid = pid();
    int dispatch = 0;
    RunnerContext runnerContext = () -> store;
    JvmContext jvmContext = () -> store;
    TestContext testContext = () -> store;
    List<Extension> extensions = null;
    try {
      while (true) {
        Message next = requests.take();
        if (next instanceof WorkerShutdown) {
          return;
        }
        TestStart request = (TestStart) next;
        loader.setClasspath(request.classpath);
        // Compilers and other tools also discover test dependencies through
        // the conventional system property rather than the loader's URLs.
        System.setProperty(
            "java.class.path",
            bootstrapClasspath
                + java.io.File.pathSeparator
                + String.join(java.io.File.pathSeparator, request.classpath));
        // Only the supplied execution metadata changes; other properties and
        // application state deliberately survive the preceding test.
        NativeTestContext.apply(request.environment, request.properties.get("user.dir"));
        request.properties.forEach(System::setProperty);
        if (Boolean.getBoolean("bazel.persistent.coverage")) {
          // Extensions can initialize instrumented classes before suite collection.
          // They must not install the agent's ordinary shutdown-time dump.
          System.setProperty("jacoco-agent.output", "none");
        }
        // Runfiles metadata belongs to this dispatch. Retained Runfiles objects
        // keep their original context; new preload calls use the current one.
        System.setProperty("bazel.runfiles.context", token + ":" + (++dispatch));
        if (extensions == null) {
          extensions = loadExtensions(loader);
          for (Extension extension : extensions) {
            extension.onWorkerStart(runnerContext);
            extension.onJvmStart(jvmContext);
          }
        }
        System.err.println(
            "JVM_REUSE target="
                + request.environment.get("TEST_TARGET")
                + " suite="
                + System.getProperty("bazel.test_suite")
                + " pid="
                + pid
                + " token="
                + token
                + " dispatch="
                + dispatch);
        int result = 1;
        ReusableCoverage coverage = null;
        try {
          if (Boolean.getBoolean("bazel.persistent.coverage")) {
            coverage = new ReusableCoverage(loader, request.classpath, request.environment);
          }
          for (Extension extension : extensions) {
            extension.onTestStart(testContext);
          }
          result = runJUnit(loader, request.arguments.toArray(new String[0]));
        } catch (Throwable e) {
          Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
          cause.printStackTrace();
        } finally {
          for (int i = extensions.size() - 1; i >= 0; i--) {
            try {
              extensions.get(i).onTestShutdown(testContext);
            } catch (Throwable e) {
              e.printStackTrace();
              result = 1;
            }
          }
          if (coverage != null) {
            try {
              coverage.close();
            } catch (Exception e) {
              e.printStackTrace();
              result = 1;
            }
          }
        }
        System.out.flush();
        System.err.flush();
        if (loader.mustRetire()) {
          // Announce retirement before the result so the coordinator cannot race
          // a subsequent dispatch against this child's exit.
          channel.send(WorkerShutdown.INSTANCE);
        }
        channel.send(new TestFinished(result));
        if (loader.mustRetire()) {
          return;
        }
      }
    } catch (Throwable e) {
      e.printStackTrace();
      throw e;
    } finally {
      if (extensions != null) {
        for (int i = extensions.size() - 1; i >= 0; i--) {
          extensions.get(i).onJvmShutdown(jvmContext);
          extensions.get(i).onWorkerShutdown(runnerContext);
        }
      }
      loader.close();
      channel.close();
    }
  }

  private static int runJUnit(ClassLoader loader, String[] arguments) throws Exception {
    Class<?> runner =
        Class.forName("com.google.testing.junit.runner.BazelTestRunner", true, loader);
    try {
      return (Integer)
          runner.getMethod("runTests", String[].class).invoke(null, (Object) arguments);
    } catch (NoSuchMethodException e) {
      // Compatibility with Bazel versions preceding the public returning entry.
      Method method = runner.getDeclaredMethod("runTestsInSuite", String.class, String[].class);
      method.setAccessible(true);
      return (Integer) method.invoke(null, System.getProperty("bazel.test_suite"), arguments);
    }
  }

  private static PrintStream wireOutput(WireChannel channel, boolean stderr) throws IOException {
    return new PrintStream(
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
          }

          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
              int chunk = Math.min(length, 16384);
              channel.send(
                  new TestOutput(stderr, Arrays.copyOfRange(bytes, offset, offset + chunk)));
              offset += chunk;
              length -= chunk;
            }
          }
        },
        true,
        "UTF-8");
  }

  private static String pid() throws IOException {
    for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
      if (line.startsWith("Pid:")) {
        return line.substring(4).trim();
      }
    }
    throw new IOException("Could not read child PID");
  }

  private static List<Extension> loadExtensions(ClassLoader loader) throws Exception {
    List<Extension> extensions = new ArrayList<>();
    String manifest = System.getProperty("bazel.persistent.extensions_file");
    if (manifest != null && !manifest.isEmpty()) {
      for (String name : Files.readAllLines(Paths.get(manifest), StandardCharsets.UTF_8)) {
        if (!name.trim().isEmpty()) {
          extensions.add(
              (Extension) Class.forName(name.trim(), true, loader).getConstructor().newInstance());
        }
      }
    }
    extensions.sort(Comparator.comparingInt(Extension::priority));
    return extensions;
  }
}
