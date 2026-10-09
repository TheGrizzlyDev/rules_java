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
#include <jni.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {
void Fail(JNIEnv* env, const char* operation) {
  std::string message = std::string(operation) + ": " + std::strerror(errno);
  env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}

bool Bytes(JNIEnv* env, jbyteArray bytes, std::string* result) {
  result->resize(env->GetArrayLength(bytes));
  if (!result->empty()) {
    env->GetByteArrayRegion(bytes, 0, result->size(),
                           reinterpret_cast<jbyte*>(&(*result)[0]));
  }
  return !env->ExceptionCheck();
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_java_testrunner_NativeTestContext_updateProcess(
    JNIEnv* env, jclass, jobjectArray entries, jbyteArray directory) {
  // Copy Java data first: no JNI allocation or callback while environ changes.
  std::string cwd;
  if (!Bytes(env, directory, &cwd)) return;
  std::vector<std::string> environment(env->GetArrayLength(entries));
  for (jsize i = 0; i < env->GetArrayLength(entries); ++i) {
    auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(entries, i));
    if (bytes == nullptr || !Bytes(env, bytes, &environment[i])) return;
    env->DeleteLocalRef(bytes);
    if (environment[i].find('=') == std::string::npos ||
        environment[i].find('\0') != std::string::npos) {
      env->ThrowNew(env->FindClass("java/io/IOException"), "Invalid encoded environment");
      return;
    }
  }
  if (cwd.find('\0') != std::string::npos) {
    env->ThrowNew(env->FindClass("java/io/IOException"), "Invalid encoded working directory");
    return;
  }
  if (chdir(cwd.c_str()) != 0) {
    Fail(env, "chdir");
    return;
  }
  if (clearenv() != 0) {
    Fail(env, "clearenv");
    return;
  }
  for (const std::string& entry : environment) {
    size_t separator = entry.find('=');
    if (setenv(entry.substr(0, separator).c_str(), entry.c_str() + separator + 1, 1) != 0) {
      Fail(env, "setenv");
      return;
    }
  }
}
