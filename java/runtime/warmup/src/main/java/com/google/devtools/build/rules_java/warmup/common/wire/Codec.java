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
package com.google.devtools.build.rules_java.warmup.common.wire;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled binary codec for the warmup wire schema.
 *
 * <p>Frame format: 1-byte type tag, varint payload length, payload bytes. Within payloads: ints
 * are varint-encoded, strings are varint UTF-8 length + bytes, lists are varint count + elements.
 *
 * <p>Both ends agree on the schema in-tree; unknown tags on read are a hard error.
 */
public final class Codec {

  private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

  /** Tag bytes identifying each top-level wire message. */
  public enum Tag {
    SERVER_EVENT_WARMUP_UPDATE(1),
    SERVER_EVENT_STATE_STALE(2),
    SERVER_EVENT_PROMOTE(3),
    TELEMETRY_SAMPLE(4),
    SNAPSHOT_REQUEST(5),
    PROFILE(6);

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

  // -------- write side --------

  public static void writeServerEvent(OutputStream out, ServerEvent event) throws IOException {
    if (event instanceof WarmupUpdate) {
      writeFramed(out, Tag.SERVER_EVENT_WARMUP_UPDATE, p -> writeWarmupUpdate(p, (WarmupUpdate) event));
    } else if (event instanceof StateStaleNotice) {
      writeFramed(out, Tag.SERVER_EVENT_STATE_STALE, p -> writeStateStale(p, (StateStaleNotice) event));
    } else if (event instanceof PromoteRequest) {
      writeFramed(out, Tag.SERVER_EVENT_PROMOTE, p -> writePromote(p, (PromoteRequest) event));
    } else {
      throw new IllegalArgumentException("unknown ServerEvent type: " + event.getClass());
    }
  }

  public static void writeTelemetrySample(OutputStream out, TelemetrySample sample)
      throws IOException {
    writeFramed(out, Tag.TELEMETRY_SAMPLE, p -> {
      writePreloadList(p, sample.loadedClasses());
      writeCompileList(p, sample.hotMethods());
    });
  }

  public static void writeSnapshotRequest(OutputStream out, SnapshotRequest req) throws IOException {
    writeFramed(out, Tag.SNAPSHOT_REQUEST, p -> {});
  }

  public static void writeProfile(OutputStream out, Profile profile) throws IOException {
    writeFramed(out, Tag.PROFILE, p -> {
      writePreloadList(p, profile.preload());
      writeCompileList(p, profile.compile());
    });
  }

  // -------- read side --------

  /** Reads the next message from the stream and returns it. Caller dispatches on the type. */
  public static Object read(InputStream in) throws IOException {
    int tagByte = in.read();
    if (tagByte < 0) {
      throw new EOFException("stream closed before next message");
    }
    Tag tag = Tag.fromCode(tagByte);
    int length = readVarintInt(in);
    if (length < 0 || length > MAX_PAYLOAD) {
      throw new IOException("payload length out of range: " + length);
    }
    byte[] payload = readFully(in, length);
    InputStream p = new java.io.ByteArrayInputStream(payload);
    switch (tag) {
      case SERVER_EVENT_WARMUP_UPDATE:
        return readWarmupUpdate(p);
      case SERVER_EVENT_STATE_STALE:
        return readStateStale(p);
      case SERVER_EVENT_PROMOTE:
        return readPromote(p);
      case TELEMETRY_SAMPLE:
        return new TelemetrySample(readPreloadList(p), readCompileList(p));
      case SNAPSHOT_REQUEST:
        return SnapshotRequest.INSTANCE;
      case PROFILE:
        return new Profile(readPreloadList(p), readCompileList(p));
    }
    throw new AssertionError("unhandled tag " + tag);
  }

  // -------- per-message body writers --------

  private static void writeWarmupUpdate(OutputStream p, WarmupUpdate u) throws IOException {
    writePreloadList(p, u.preload());
    writeCompileList(p, u.compile());
  }

  private static void writeStateStale(OutputStream p, StateStaleNotice n) throws IOException {
    writeString(p, n.fqClassName());
    writeString(p, n.warmedDigestHex());
    writeString(p, n.reportedDigestHex());
    writeString(p, n.reason());
  }

  private static void writePromote(OutputStream p, PromoteRequest r) throws IOException {
    writeString(p, r.warmingJvmId());
    writeString(p, r.reason());
  }

  // -------- per-message body readers --------

  private static WarmupUpdate readWarmupUpdate(InputStream p) throws IOException {
    return new WarmupUpdate(readPreloadList(p), readCompileList(p));
  }

  private static StateStaleNotice readStateStale(InputStream p) throws IOException {
    return new StateStaleNotice(readString(p), readString(p), readString(p), readString(p));
  }

  private static PromoteRequest readPromote(InputStream p) throws IOException {
    return new PromoteRequest(readString(p), readString(p));
  }

  // -------- list helpers --------

  private static void writePreloadList(OutputStream p, List<Profile.PreloadEntry> entries)
      throws IOException {
    writeVarintInt(p, entries.size());
    for (Profile.PreloadEntry entry : entries) {
      writeString(p, entry.fqClassName());
      writeString(p, entry.sha256DigestHex());
    }
  }

  private static List<Profile.PreloadEntry> readPreloadList(InputStream p) throws IOException {
    int count = readVarintInt(p);
    List<Profile.PreloadEntry> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(new Profile.PreloadEntry(readString(p), readString(p)));
    }
    return out;
  }

  private static void writeCompileList(OutputStream p, List<Profile.CompileEntry> entries)
      throws IOException {
    writeVarintInt(p, entries.size());
    for (Profile.CompileEntry entry : entries) {
      writeString(p, entry.fqClassName());
      writeString(p, entry.methodName());
      writeString(p, entry.descriptor());
      writeVarintInt(p, entry.tier());
    }
  }

  private static List<Profile.CompileEntry> readCompileList(InputStream p) throws IOException {
    int count = readVarintInt(p);
    List<Profile.CompileEntry> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String name = readString(p);
      String method = readString(p);
      String descriptor = readString(p);
      int tier = readVarintInt(p);
      out.add(new Profile.CompileEntry(name, method, descriptor, tier));
    }
    return out;
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
