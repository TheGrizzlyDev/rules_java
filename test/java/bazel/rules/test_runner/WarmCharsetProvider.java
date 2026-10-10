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

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.spi.CharsetProvider;
import java.util.Collections;
import java.util.Iterator;

/** Test-jar service provider exercising JDK system-classloader discovery. */
public final class WarmCharsetProvider extends CharsetProvider {
  private static final Charset CHARSET =
      new Charset("X-WARM-TEST-CHARSET", new String[0]) {
        @Override
        public boolean contains(Charset other) {
          return other == this || StandardCharsets.UTF_8.contains(other);
        }

        @Override
        public CharsetDecoder newDecoder() {
          return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
          return StandardCharsets.UTF_8.newEncoder();
        }
      };

  @Override
  public Iterator<Charset> charsets() {
    return Collections.singleton(CHARSET).iterator();
  }

  @Override
  public Charset charsetForName(String name) {
    return CHARSET.name().equalsIgnoreCase(name) ? CHARSET : null;
  }
}
