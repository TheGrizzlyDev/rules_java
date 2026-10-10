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

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Exercises the JVM's instrumentation hook on the real retained system loader. */
public final class InstrumentationClassLoaderTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("warm-instrumentation-").toAbsolutePath();
    Path agent = root.resolve("agent.jar");
    Path probe = root.resolve("probe.jar");
    Path payload = root.resolve("payload.jar");
    writeJar(agent, Agent.class, true);
    writeJar(probe, Probe.class, false);
    writeJar(payload, Payload.class, false);
    Path driver =
        Paths.get(
            WarmClassLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    try {
      for (boolean known : new boolean[] {false, true}) {
        Process process =
            new ProcessBuilder(
                    Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.system.class.loader=" + WarmClassLoader.class.getName(),
                    "-javaagent:" + agent + "=" + payload + "," + known,
                    "-classpath",
                    driver
                        + java.io.File.pathSeparator
                        + probe
                        + java.io.File.pathSeparator
                        + agent,
                    Probe.class.getName(),
                    payload.toString(),
                    Boolean.toString(known))
                .inheritIO()
                .start();
        try {
          if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("Agent append failed with retained system loader");
          }
        } finally {
          process.destroyForcibly();
        }
      }
    } finally {
      Files.deleteIfExists(agent);
      Files.deleteIfExists(probe);
      Files.deleteIfExists(payload);
      Files.deleteIfExists(root);
    }
    System.out.println("PASS JVM agent append, class loading, and retained per-request discovery");
  }

  private static void writeJar(Path path, Class<?> type, boolean agent) throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (agent) {
      manifest.getMainAttributes().putValue("Premain-Class", type.getName());
    }
    String resource = type.getName().replace('.', '/') + ".class";
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest);
        InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) {
        throw new AssertionError("Missing fixture bytecode: " + resource);
      }
      output.putNextEntry(new JarEntry(resource));
      byte[] bytes = new byte[4096];
      int count;
      while ((count = input.read(bytes)) != -1) {
        output.write(bytes, 0, count);
      }
      output.closeEntry();
    }
  }

  public static final class Agent {
    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
      String[] arguments = argument.split(",");
      if (Boolean.parseBoolean(arguments[1])) {
        ((WarmClassLoader) ClassLoader.getSystemClassLoader())
            .setClasspath(Arrays.asList(arguments[0]));
      }
      try (JarFile jar = new JarFile(arguments[0])) {
        instrumentation.appendToSystemClassLoaderSearch(jar);
      }
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      WarmClassLoader loader = (WarmClassLoader) ClassLoader.getSystemClassLoader();
      boolean known = Boolean.parseBoolean(args[1]);
      if (loader.mustRetire() == known) {
        throw new AssertionError("Unknown agent jar retirement was not recorded correctly");
      }
      loader.setClasspath(Collections.emptyList());
      String name =
          "com.google.devtools.build.java.testrunner.InstrumentationClassLoaderTest$Payload";
      String resource = name.replace('.', '/') + ".class";
      Class<?> payload = Class.forName(name, true, loader);
      if (payload.getClassLoader() != loader
          || loader.getURLs().length != 0
          || loader.findResource(resource) != null) {
        throw new AssertionError(
            "Agent append changed dispatch discovery or missed the warm loader");
      }
      loader.setClasspath(Arrays.asList(args[0]));
      if (loader.findResource(resource) == null || loader.getURLs().length != 1) {
        throw new AssertionError("Current dispatch did not discover the payload jar");
      }
      payload.getMethod("increment").invoke(null);
      loader.setClasspath(Collections.emptyList());
      if (Class.forName(name, true, loader) != payload
          || !Integer.valueOf(2).equals(payload.getMethod("increment").invoke(null))
          || loader.findResource(resource) != null
          || loader.getURLs().length != 0) {
        throw new AssertionError(
            "Agent classes lost warm identity or leaked into resource discovery");
      }
    }
  }

  public static final class Payload {
    private static int count;

    public static int increment() {
      return ++count;
    }
  }
}
