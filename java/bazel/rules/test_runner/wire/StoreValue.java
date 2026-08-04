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

/**
 * Tagged sum type for values traveling on the wire. The payload variant is determined by
 * {@link #kind()}; only the matching accessor returns a meaningful value.
 */
public final class StoreValue {
  public enum Kind {
    STRING(0),
    BOOLEAN(1),
    LONG(2),
    DOUBLE(3);

    private final int code;

    Kind(int code) {
      this.code = code;
    }

    public int code() {
      return code;
    }

    public static Kind fromCode(int code) {
      for (Kind k : values()) {
        if (k.code == code) {
          return k;
        }
      }
      throw new IllegalArgumentException("unknown StoreValue kind: " + code);
    }
  }

  private final Kind kind;
  private final String stringValue;
  private final boolean boolValue;
  private final long longValue;
  private final double doubleValue;

  private StoreValue(Kind kind, String s, boolean b, long l, double d) {
    this.kind = kind;
    this.stringValue = s;
    this.boolValue = b;
    this.longValue = l;
    this.doubleValue = d;
  }

  public static StoreValue ofString(String v) {
    return new StoreValue(Kind.STRING, v, false, 0L, 0.0);
  }

  public static StoreValue ofBoolean(boolean v) {
    return new StoreValue(Kind.BOOLEAN, "", v, 0L, 0.0);
  }

  public static StoreValue ofLong(long v) {
    return new StoreValue(Kind.LONG, "", false, v, 0.0);
  }

  public static StoreValue ofDouble(double v) {
    return new StoreValue(Kind.DOUBLE, "", false, 0L, v);
  }

  public Kind kind() {
    return kind;
  }

  public String stringValue() {
    return stringValue;
  }

  public boolean boolValue() {
    return boolValue;
  }

  public long longValue() {
    return longValue;
  }

  public double doubleValue() {
    return doubleValue;
  }
}
