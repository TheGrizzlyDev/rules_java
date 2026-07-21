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

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 digest of a class's on-disk bytes.
 *
 * <p>Reads {@code <fq/class/Name>.class} from the given classloader and returns lowercase hex. The
 * warming engine uses this to validate that classes it's about to preload match the digests the
 * coordinator provided in the profile.
 */
final class ClassDigest {

  private ClassDigest() {}

  /** Returns the lowercase hex SHA-256, or {@code null} if the class file is not on the loader. */
  static String forClass(String fqClassName, ClassLoader loader) throws IOException {
    String resource = fqClassName.replace('.', '/') + ".class";
    try (InputStream in = loader.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      MessageDigest md;
      try {
        md = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new AssertionError("SHA-256 not available", e);
      }
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        md.update(buf, 0, n);
      }
      return toHex(md.digest());
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
