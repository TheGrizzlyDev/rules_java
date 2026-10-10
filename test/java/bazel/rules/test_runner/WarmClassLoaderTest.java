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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Checks dispatch-local discovery without losing loaded classes or their static state. */
public final class WarmClassLoaderTest {
  public static final class Fixture {
    public static int count;
  }

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("warm-classpath-");
    Path first = directory.resolve("first.jar");
    Path second = directory.resolve("second.jar");
    String className = Fixture.class.getName();
    String classResource = className.replace('.', '/') + ".class";
    try {
      try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(first))) {
        entry(jar, "first-only.txt", "first".getBytes(StandardCharsets.UTF_8));
        entry(jar, "META-INF/services/example.Service", "first".getBytes(StandardCharsets.UTF_8));
        jar.putNextEntry(new JarEntry(classResource));
        try (InputStream input =
            WarmClassLoaderTest.class.getResourceAsStream("/" + classResource)) {
          byte[] buffer = new byte[4096];
          int size;
          while ((size = input.read(buffer)) != -1) {
            jar.write(buffer, 0, size);
          }
        }
        jar.closeEntry();
      }
      try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(second))) {
        entry(jar, "second-only.txt", "second".getBytes(StandardCharsets.UTF_8));
        entry(jar, "META-INF/services/example.Service", "second".getBytes(StandardCharsets.UTF_8));
      }
      try (WarmClassLoader loader = new WarmClassLoader(null)) {
        loader.setClasspath(Collections.singletonList(first.toString()));
        Class<?> loaded = loader.loadClass(className);
        loaded.getField("count").setInt(null, 42);
        check(loader.getResource("first-only.txt") != null, "First resource missing");
        loader.setClasspath(Collections.singletonList(second.toString()));
        check(
            Arrays.equals(loader.getURLs(), new URL[] {second.toUri().toURL()}),
            "Scanner discovered a preceding target's jar");
        check(loader.getResource("first-only.txt") == null, "Old target resource leaked");
        check(loader.getResource("second-only.txt") != null, "Current resource missing");
        Enumeration<URL> services = loader.getResources("META-INF/services/example.Service");
        check(services.hasMoreElements(), "Current service missing");
        check(services.nextElement().toString().contains("second.jar"), "Old service leaked");
        check(!services.hasMoreElements(), "Old service included in enumeration");
        check(loader.loadClass(className) == loaded, "Loaded class lost its identity");
        check(loaded.getField("count").getInt(null) == 42, "Static state was reset");
      }
    } finally {
      Files.deleteIfExists(first);
      Files.deleteIfExists(second);
      Files.deleteIfExists(directory);
    }
  }

  private static void entry(JarOutputStream jar, String name, byte[] content) throws Exception {
    jar.putNextEntry(new JarEntry(name));
    jar.write(content);
    jar.closeEntry();
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
