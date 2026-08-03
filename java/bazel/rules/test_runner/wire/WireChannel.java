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
package com.google.devtools.build.java.testrunner.wire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Synchronized framed-message channel over a byte stream pair. Callers exchange {@link Message}s
 * without worrying about framing or concurrent writes.
 */
public final class WireChannel implements AutoCloseable {

  private final InputStream in;
  private final OutputStream out;
  private final Object writeLock = new Object();

  public WireChannel(InputStream in, OutputStream out) {
    this.in = new BufferedInputStream(in);
    this.out = new BufferedOutputStream(out);
  }

  /** Reads the next message from the channel. Returns {@code null} on clean EOF. */
  public Message read() throws IOException {
    return Codec.read(in);
  }

  /** Writes a message and flushes. Multiple threads may call this concurrently. */
  public void send(Message msg) throws IOException {
    synchronized (writeLock) {
      Codec.write(out, msg);
      out.flush();
    }
  }

  @Override
  public void close() throws IOException {
    IOException first = null;
    try {
      in.close();
    } catch (IOException e) {
      first = e;
    }
    try {
      out.close();
    } catch (IOException e) {
      if (first == null) {
        first = e;
      } else {
        first.addSuppressed(e);
      }
    }
    if (first != null) {
      throw first;
    }
  }
}
