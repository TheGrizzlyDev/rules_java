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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Updates runner-owned process metadata; application state stays warm. */
final class NativeTestContext {
  private static final Charset NATIVE_CHARSET =
      Charset.forName(System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));

  static {
    // The library is part of the bootstrap jar, whose digest gates child reuse.
    try (InputStream resource =
        NativeTestContext.class.getResourceAsStream("/libtest_context.so")) {
      if (resource == null) {
        throw new IOException("Missing Linux test context library");
      }
      Path library = Files.createTempFile("persistent-test-context-", ".so");
      try {
        Files.copy(resource, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.load(library.toAbsolutePath().toString());
      } finally {
        // Linux keeps the loaded mapping alive after unlinking the file.
        Files.deleteIfExists(library);
      }
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private NativeTestContext() {}

  @SuppressWarnings("unchecked")
  static void apply(Map<String, String> environment, String directory) throws Exception {
    if (directory == null || !new File(directory).isAbsolute()) {
      throw new IOException("Missing absolute per-request working directory");
    }
    byte[][] entries = new byte[environment.size()][];
    int index = 0;
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key.isEmpty()
          || key.indexOf('=') >= 0
          || key.indexOf('\0') >= 0
          || value.indexOf('\0') >= 0) {
        throw new IOException("Invalid per-request environment entry");
      }
      entries[index++] = (key + "=" + value).getBytes(NATIVE_CHARSET);
    }
    // Resolve all JDK fields before changing process state. A failure aborts the
    // request and retires the child instead of executing with a partial context.
    Field backing = System.getenv().getClass().getDeclaredField("m");
    backing.setAccessible(true);
    Map<String, String> values = (Map<String, String>) backing.get(System.getenv());
    Field fileSystem;
    try {
      fileSystem = File.class.getDeclaredField("fs");
    } catch (NoSuchFieldException e) {
      // Recent JDKs renamed the same singleton to FS.
      fileSystem = File.class.getDeclaredField("FS");
    }
    fileSystem.setAccessible(true);
    Object io = fileSystem.get(null);
    Field userDir = null;
    try {
      userDir = io.getClass().getDeclaredField("userDir");
      userDir.setAccessible(true);
    } catch (NoSuchFieldException e) {
      // Java 8's java.io implementation reads user.dir on each resolution.
      if (!System.getProperty("java.specification.version").equals("1.8")) {
        throw e;
      }
    }
    Object nio = FileSystems.getDefault();
    Field defaultDirectory =
        Class.forName("sun.nio.fs.UnixFileSystem").getDeclaredField("defaultDirectory");
    defaultDirectory.setAccessible(true);
    byte[] cwd = directory.getBytes(NATIVE_CHARSET);
    updateProcess(entries, cwd);
    values.clear();
    values.putAll(environment);
    System.setProperty("user.dir", directory);
    if (userDir != null) {
      userDir.set(io, directory);
    }
    defaultDirectory.set(nio, cwd);
  }

  private static native void updateProcess(byte[][] environment, byte[] directory)
      throws IOException;
}
