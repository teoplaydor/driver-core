// Stand-in for LiteRT-LM's liblitertlm_jni.so (desktop tests): the JNI glue of the embedding functions is
// copied from LiteRT-LM v0.18.0 kotlin/java/com/google/ai/edge/litertlm/jni/litertlm.cc (Apache 2.0,
// Copyright 2025 Google LLC) — the same FindClass / GetMethodID / NewObject calls the real library makes
// on our Java classes — with the engine replaced by a deterministic fake that records what it was given.
#include <jni.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

#define JNI_METHOD(METHOD_NAME) Java_com_google_ai_edge_litertlm_LiteRtLmJni_##METHOD_NAME

namespace {

struct InputData {
  enum Kind { kText, kAudio, kImage } kind;
  std::string data;
};

std::string g_last_call;

void ThrowLiteRtLmJniException(JNIEnv* env, const std::string& message) {
  jclass exClass =
      env->FindClass("com/google/ai/edge/litertlm/LiteRtLmJniException");
  if (exClass != nullptr) {
    env->ThrowNew(exClass, message.c_str());
    // Clean up local reference
    env->DeleteLocalRef(exClass);
  }
}

// Converts a Java InputData array to a C++ vector of InputData. (v0.18.0, verbatim but for InputData)
std::vector<InputData> GetNativeInputData(JNIEnv* env,
                                          jobjectArray input_data) {
  jclass text_class =
      env->FindClass("com/google/ai/edge/litertlm/InputData$Text");
  jclass audio_class =
      env->FindClass("com/google/ai/edge/litertlm/InputData$Audio");
  jclass image_class =
      env->FindClass("com/google/ai/edge/litertlm/InputData$Image");

  jmethodID text_get_text_mid =
      env->GetMethodID(text_class, "getText", "()Ljava/lang/String;");
  jmethodID audio_get_bytes_mid =
      env->GetMethodID(audio_class, "getBytes", "()[B");
  jmethodID image_get_bytes_mid =
      env->GetMethodID(image_class, "getBytes", "()[B");

  jsize num_inputs = env->GetArrayLength(input_data);
  std::vector<InputData> contents;
  contents.reserve(num_inputs);
  for (jsize i = 0; i < num_inputs; ++i) {
    jobject input_obj = env->GetObjectArrayElement(input_data, i);
    if (env->IsInstanceOf(input_obj, text_class)) {
      jstring text_jstr =
          (jstring)env->CallObjectMethod(input_obj, text_get_text_mid);
      const char* text_chars = env->GetStringUTFChars(text_jstr, nullptr);
      contents.push_back({InputData::kText, text_chars});
      env->ReleaseStringUTFChars(text_jstr, text_chars);
      env->DeleteLocalRef(text_jstr);
    } else if (env->IsInstanceOf(input_obj, audio_class)) {
      jbyteArray bytes_jarr =
          (jbyteArray)env->CallObjectMethod(input_obj, audio_get_bytes_mid);
      jsize len = env->GetArrayLength(bytes_jarr);
      jbyte* bytes = env->GetByteArrayElements(bytes_jarr, nullptr);
      contents.push_back(
          {InputData::kAudio, std::string(reinterpret_cast<char*>(bytes), len)});
      env->ReleaseByteArrayElements(bytes_jarr, bytes, JNI_ABORT);
      env->DeleteLocalRef(bytes_jarr);
    } else if (env->IsInstanceOf(input_obj, image_class)) {
      jbyteArray bytes_jarr =
          (jbyteArray)env->CallObjectMethod(input_obj, image_get_bytes_mid);
      jsize len = env->GetArrayLength(bytes_jarr);
      jbyte* bytes = env->GetByteArrayElements(bytes_jarr, nullptr);
      contents.push_back(
          {InputData::kImage, std::string(reinterpret_cast<char*>(bytes), len)});
      env->ReleaseByteArrayElements(bytes_jarr, bytes, JNI_ABORT);
      env->DeleteLocalRef(bytes_jarr);
    } else {
      ThrowLiteRtLmJniException(env, "Unsupported InputData type");
    }
    env->DeleteLocalRef(input_obj);
  }

  env->DeleteLocalRef(text_class);
  env->DeleteLocalRef(audio_class);
  env->DeleteLocalRef(image_class);

  return contents;
}

std::optional<int> GetOptionalInt(JNIEnv* env, jobject integer_obj) {
  if (integer_obj == nullptr) return std::nullopt;
  jclass integer_class = env->FindClass("java/lang/Integer");
  jmethodID int_value_mid = env->GetMethodID(integer_class, "intValue", "()I");
  jint value = env->CallIntMethod(integer_obj, int_value_mid);
  env->DeleteLocalRef(integer_class);
  return value;
}

std::optional<bool> GetOptionalBoolean(JNIEnv* env, jobject boolean_obj) {
  if (boolean_obj == nullptr) return std::nullopt;
  jclass boolean_class = env->FindClass("java/lang/Boolean");
  jmethodID boolean_value_mid =
      env->GetMethodID(boolean_class, "booleanValue", "()Z");
  jboolean value = env->CallBooleanMethod(boolean_obj, boolean_value_mid);
  env->DeleteLocalRef(boolean_class);
  return (value == JNI_TRUE);
}

std::string JStr(JNIEnv* env, jstring s) {
  if (s == nullptr) return "<null>";
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

// ------------------------------------------------------------------ fake engine

struct FakeEngine {
  std::string backend;
};

std::string Opt(const std::optional<int>& v) { return v ? std::to_string(*v) : "-"; }
std::string Opt(const std::optional<bool>& v) { return v ? (*v ? "true" : "false") : "-"; }

std::string Describe(const std::vector<InputData>& in) {
  std::string d;
  for (const auto& x : in) {
    if (!d.empty()) d += ",";
    if (x.kind == InputData::kText) {
      d += "text[" + x.data + "]";
    } else {
      bool png = x.data.size() > 4 && (unsigned char)x.data[0] == 0x89 && x.data[1] == 'P';
      bool jpg = x.data.size() > 3 && (unsigned char)x.data[0] == 0xFF && (unsigned char)x.data[1] == 0xD8;
      d += std::string(x.kind == InputData::kImage ? "image" : "audio") + (png ? "/png" : jpg ? "/jpeg" : "/?");
    }
  }
  return d;
}

// Deterministic vector from the content (equal inputs give equal vectors).
std::vector<float> Embed(const std::vector<InputData>& in) {
  std::vector<float> v(16, 0.f);
  unsigned h = 2166136261u;
  for (const auto& x : in) {
    for (unsigned char c : x.data) h = (h ^ c) * 16777619u;
    h = (h ^ (unsigned)x.kind) * 16777619u;
  }
  for (int i = 0; i < 16; ++i) {
    h = h * 1103515245u + 12345u;
    v[i] = ((h >> 8) & 0xffff) / 65535.f - 0.5f;
  }
  return v;
}

bool TooLong(const std::vector<InputData>& in) {
  for (const auto& x : in) if (x.kind == InputData::kText && x.data.size() > 4000) return true;
  return false;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreateEmbeddingEngine)(
    JNIEnv* env, jclass thiz, jint model_fd, jstring model_path,
    jstring backend, jstring vision_backend, jstring audio_backend,
    jstring cache_dir, jstring main_npu_native_library_dir,
    jstring vision_npu_native_library_dir, jstring audio_npu_native_library_dir,
    jint main_backend_num_threads, jint audio_backend_num_threads,
    jint max_input_length, jint vision_tokens_per_image,
    jint activation_data_type) {
  std::string path = JStr(env, model_path);
  g_last_call = "create fd=" + std::to_string(model_fd) + " path=" + path + " backend=" + JStr(env, backend) +
                " vision=" + JStr(env, vision_backend) + " audio=" + JStr(env, audio_backend) +
                " cache=" + JStr(env, cache_dir) + " npu=" + JStr(env, main_npu_native_library_dir) + "|" +
                JStr(env, vision_npu_native_library_dir) + "|" + JStr(env, audio_npu_native_library_dir) +
                " threads=" + std::to_string(main_backend_num_threads) + "/" + std::to_string(audio_backend_num_threads) +
                " max_input=" + std::to_string(max_input_length) + " vision_tokens=" + std::to_string(vision_tokens_per_image) +
                " activation=" + std::to_string(activation_data_type);
  FILE* f = fopen(path.c_str(), "rb");
  if (f == nullptr) {
    ThrowLiteRtLmJniException(env, "Failed to open model file: NOT_FOUND " + path);
    return 0;
  }
  fclose(f);
  return reinterpret_cast<jlong>(new FakeEngine{JStr(env, backend)});
}

JNIEXPORT void JNICALL JNI_METHOD(nativeDeleteEmbeddingEngine)(
    JNIEnv* env, jclass thiz, jlong embedding_engine_pointer) {
  if (embedding_engine_pointer != 0) {
    delete reinterpret_cast<FakeEngine*>(embedding_engine_pointer);
    g_last_call = "delete";
  }
}

JNIEXPORT jobject JNICALL JNI_METHOD(nativeComputeEmbedding)(
    JNIEnv* env, jclass thiz, jlong embedding_engine_pointer,
    jobjectArray input_data, jobject normalize, jobject insert_special_tokens,
    jobject output_size, jobject vision_tokens_per_image) {
  auto* engine = reinterpret_cast<FakeEngine*>(embedding_engine_pointer);
  if (!engine) {
    ThrowLiteRtLmJniException(env, "EmbeddingEngine pointer is null.");
    return nullptr;
  }

  std::vector<InputData> contents = GetNativeInputData(env, input_data);
  if (env->ExceptionCheck()) {
    return nullptr;
  }
  auto opt_norm = GetOptionalBoolean(env, normalize);
  auto opt_tokens = GetOptionalBoolean(env, insert_special_tokens);
  auto opt_size = GetOptionalInt(env, output_size);
  auto opt_vision_tokens = GetOptionalInt(env, vision_tokens_per_image);
  g_last_call = "embed " + Describe(contents) + " normalize=" + Opt(opt_norm) + " special=" + Opt(opt_tokens) +
                " size=" + Opt(opt_size) + " vision_tokens=" + Opt(opt_vision_tokens);
  if (TooLong(contents)) {
    ThrowLiteRtLmJniException(env, "ComputeEmbedding failed: INVALID_ARGUMENT: input too long");
    return nullptr;
  }

  auto vec = Embed(contents);

  jclass response_cls =
      env->FindClass("com/google/ai/edge/litertlm/EmbeddingResponse");
  jmethodID ctor = env->GetMethodID(response_cls, "<init>", "([F)V");

  jfloatArray float_arr = env->NewFloatArray(vec.size());
  if (float_arr != nullptr && !vec.empty()) {
    env->SetFloatArrayRegion(float_arr, 0, vec.size(), vec.data());
  }

  jobject response_obj = env->NewObject(response_cls, ctor, float_arr);
  env->DeleteLocalRef(response_cls);
  env->DeleteLocalRef(float_arr);
  return response_obj;
}

JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeComputeEmbeddingBatch)(
    JNIEnv* env, jclass thiz, jlong embedding_engine_pointer,
    jobjectArray input_data_batch, jobject normalize,
    jobject insert_special_tokens, jobject output_size,
    jobject vision_tokens_per_image) {
  auto* engine = reinterpret_cast<FakeEngine*>(embedding_engine_pointer);
  if (!engine) {
    ThrowLiteRtLmJniException(env, "EmbeddingEngine pointer is null.");
    return nullptr;
  }

  jsize batch_size = env->GetArrayLength(input_data_batch);
  std::vector<std::vector<InputData>> contents_batch;
  contents_batch.reserve(batch_size);

  for (jsize i = 0; i < batch_size; ++i) {
    jobjectArray single_request = static_cast<jobjectArray>(
        env->GetObjectArrayElement(input_data_batch, i));
    contents_batch.push_back(GetNativeInputData(env, single_request));
    env->DeleteLocalRef(single_request);
    if (env->ExceptionCheck()) {
      return nullptr;
    }
  }
  auto opt_norm = GetOptionalBoolean(env, normalize);
  auto opt_tokens = GetOptionalBoolean(env, insert_special_tokens);
  auto opt_size = GetOptionalInt(env, output_size);
  auto opt_vision_tokens = GetOptionalInt(env, vision_tokens_per_image);
  g_last_call = "batch " + std::to_string(batch_size) + ":";
  for (const auto& c : contents_batch) g_last_call += " {" + Describe(c) + "}";
  g_last_call += " normalize=" + Opt(opt_norm) + " special=" + Opt(opt_tokens) + " size=" + Opt(opt_size) +
                 " vision_tokens=" + Opt(opt_vision_tokens);

  jclass response_cls =
      env->FindClass("com/google/ai/edge/litertlm/EmbeddingResponse");
  jmethodID ctor = env->GetMethodID(response_cls, "<init>", "([F)V");

  jobjectArray result_array =
      env->NewObjectArray(contents_batch.size(), response_cls, nullptr);

  for (size_t i = 0; i < contents_batch.size(); ++i) {
    auto vec = Embed(contents_batch[i]);

    jfloatArray float_arr = env->NewFloatArray(vec.size());
    if (float_arr != nullptr && !vec.empty()) {
      env->SetFloatArrayRegion(float_arr, 0, vec.size(), vec.data());
    }

    jobject response_obj = env->NewObject(response_cls, ctor, float_arr);
    env->SetObjectArrayElement(result_array, i, response_obj);
    env->DeleteLocalRef(response_obj);
    env->DeleteLocalRef(float_arr);
  }

  env->DeleteLocalRef(response_cls);
  return result_array;
}

// Test-only: what the fake engine was last asked.
JNIEXPORT jstring JNICALL Java_LiteRtJniTest_lastCall(JNIEnv* env, jclass) {
  return env->NewStringUTF(g_last_call.c_str());
}

}  // extern "C"
