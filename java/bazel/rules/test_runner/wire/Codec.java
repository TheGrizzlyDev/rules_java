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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hand-rolled binary codec for the coordinator ↔ child JVM wire.
 *
 * <p>Frame format: 1-byte type tag, varint payload length, payload bytes. Within payloads: ints
 * are varint-encoded, strings are varint UTF-8 length + bytes, booleans are one byte.
 *
 * <p>Both ends agree on the schema in-tree; unknown tags on read are a hard error.
 */
public final class Codec {

  private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

  /** Tag bytes identifying each top-level wire message. */
  public enum Tag {
    // Coordinator → child.
    SESSION_START(1),
    STORE_GET_RESPONSE(2),
    STORE_SET_ACK(3),
    WORKER_START(4),
    WORKER_SHUTDOWN(5),
    // Child → coordinator.
    SESSION_READY(100),
    STORE_GET_REQUEST(101),
    STORE_SET_REQUEST(102),
    TEST_FINISHED(200);

    private final int code;

    Tag(int code) {
      this.code = code;
    }

    public int code() {
      return code;
    }

    static Tag fromCode(int code) {
      for (Tag t : values()) {
        if (t.code == code) {
          return t;
        }
      }
      throw new IllegalArgumentException("unknown wire tag: " + code);
    }
  }

  private Codec() {}

  // -------- write --------

  public static void write(OutputStream out, Message msg) throws IOException {
    if (msg instanceof SessionStart) {
      writeFramed(out, Tag.SESSION_START, p -> {});
    } else if (msg instanceof SessionReady) {
      writeFramed(out, Tag.SESSION_READY, p -> {});
    } else if (msg instanceof WorkerStart) {
      writeFramed(out, Tag.WORKER_START, p -> {});
    } else if (msg instanceof WorkerShutdown) {
      writeFramed(out, Tag.WORKER_SHUTDOWN, p -> {});
    } else if (msg instanceof StoreGetRequest) {
      StoreGetRequest r = (StoreGetRequest) msg;
      writeFramed(out, Tag.STORE_GET_REQUEST, p -> {
        writeVarintInt(p, r.requestId());
        writeString(p, r.key());
      });
    } else if (msg instanceof StoreGetResponse) {
      StoreGetResponse r = (StoreGetResponse) msg;
      writeFramed(out, Tag.STORE_GET_RESPONSE, p -> {
        writeVarintInt(p, r.requestId());
        writeBool(p, r.present());
        writeString(p, r.value());
      });
    } else if (msg instanceof StoreSetRequest) {
      StoreSetRequest r = (StoreSetRequest) msg;
      writeFramed(out, Tag.STORE_SET_REQUEST, p -> {
        writeVarintInt(p, r.requestId());
        writeString(p, r.key());
        writeString(p, r.value());
      });
    } else if (msg instanceof StoreSetAck) {
      StoreSetAck r = (StoreSetAck) msg;
      writeFramed(out, Tag.STORE_SET_ACK, p -> writeVarintInt(p, r.requestId()));
    } else if (msg instanceof TestFinished) {
      TestFinished r = (TestFinished) msg;
      writeFramed(out, Tag.TEST_FINISHED, p -> writeVarintInt(p, r.exitCode()));
    } else {
      throw new IllegalArgumentException("unknown Message type: " + msg.getClass());
    }
  }

  // -------- read --------

  /** Reads the next message from the stream. Returns {@code null} on clean EOF. */
  public static Message read(InputStream in) throws IOException {
    int tagByte = in.read();
    if (tagByte < 0) {
      return null;
    }
    Tag tag = Tag.fromCode(tagByte);
    int length = readVarintInt(in);
    if (length < 0 || length > MAX_PAYLOAD) {
      throw new IOException("payload length out of range: " + length);
    }
    byte[] payload = readFully(in, length);
    InputStream p = new java.io.ByteArrayInputStream(payload);
    switch (tag) {
      case SESSION_START:
        return SessionStart.INSTANCE;
      case SESSION_READY:
        return SessionReady.INSTANCE;
      case WORKER_START:
        return WorkerStart.INSTANCE;
      case WORKER_SHUTDOWN:
        return WorkerShutdown.INSTANCE;
      case STORE_GET_REQUEST:
        return new StoreGetRequest(readVarintInt(p), readString(p));
      case STORE_GET_RESPONSE:
        return new StoreGetResponse(readVarintInt(p), readBool(p), readString(p));
      case STORE_SET_REQUEST:
        return new StoreSetRequest(readVarintInt(p), readString(p), readString(p));
      case STORE_SET_ACK:
        return new StoreSetAck(readVarintInt(p));
      case TEST_FINISHED:
        return new TestFinished(readVarintInt(p));
    }
    throw new AssertionError("unhandled tag " + tag);
  }

  // -------- framing primitives --------

  @FunctionalInterface
  private interface PayloadWriter {
    void write(OutputStream payload) throws IOException;
  }

  private static void writeFramed(OutputStream out, Tag tag, PayloadWriter writer)
      throws IOException {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    writer.write(payload);
    if (payload.size() > MAX_PAYLOAD) {
      throw new IOException("payload exceeds maximum size: " + payload.size());
    }
    out.write(tag.code());
    writeVarintInt(out, payload.size());
    payload.writeTo(out);
  }

  private static void writeString(OutputStream out, String s) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    writeVarintInt(out, bytes.length);
    out.write(bytes);
  }

  private static String readString(InputStream in) throws IOException {
    int length = readVarintInt(in);
    if (length < 0 || length > MAX_PAYLOAD) {
      throw new IOException("string length out of range: " + length);
    }
    return new String(readFully(in, length), StandardCharsets.UTF_8);
  }

  private static void writeBool(OutputStream out, boolean b) throws IOException {
    out.write(b ? 1 : 0);
  }

  private static boolean readBool(InputStream in) throws IOException {
    int b = in.read();
    if (b < 0) {
      throw new EOFException("unexpected EOF in bool");
    }
    if (b != 0 && b != 1) {
      throw new IOException("invalid bool byte: " + b);
    }
    return b == 1;
  }

  private static void writeVarintInt(OutputStream out, int value) throws IOException {
    int v = value;
    while ((v & ~0x7F) != 0) {
      out.write((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out.write(v & 0x7F);
  }

  private static int readVarintInt(InputStream in) throws IOException {
    int result = 0;
    for (int shift = 0; shift < 32; shift += 7) {
      int b = in.read();
      if (b < 0) {
        throw new EOFException("unexpected EOF in varint");
      }
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
    }
    throw new IOException("varint too long");
  }

  private static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] buf = new byte[length];
    int read = 0;
    while (read < length) {
      int n = in.read(buf, read, length - read);
      if (n < 0) {
        throw new EOFException("unexpected EOF; wanted " + length + " bytes, got " + read);
      }
      read += n;
    }
    return buf;
  }
}
