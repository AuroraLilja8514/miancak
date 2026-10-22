#include <jni.h>
#include <rime_api.h>
#include <mutex>
#include <set>
#include <string>

namespace {
std::mutex mutex;
std::set<RimeSessionId> sessions;
std::string shared_path, user_path;
RimeApi* api = nullptr;

void fail(JNIEnv* env, const char* message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

// JNI's NewStringUTF uses modified UTF-8; Rime returns standard UTF-8, including
// supplementary CJK characters. Decode with Java's UTF-8 constructor instead.
jstring string(JNIEnv* env, const char* value) {
  std::string bytes = value ? value : "";
  jbyteArray data = env->NewByteArray(bytes.size());
  env->SetByteArrayRegion(data, 0, bytes.size(), reinterpret_cast<const jbyte*>(bytes.data()));
  jclass cls = env->FindClass("java/lang/String");
  jstring encoding = env->NewStringUTF("UTF-8");
  auto result = static_cast<jstring>(env->NewObject(cls,
      env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V"), data, encoding));
  env->DeleteLocalRef(data);
  env->DeleteLocalRef(encoding);
  env->DeleteLocalRef(cls);
  return result;
}

bool valid(JNIEnv* env, jlong id) {
  if (!api || !sessions.count(static_cast<RimeSessionId>(id))) {
    fail(env, "Invalid Rime session");
    return false;
  }
  return true;
}

jobject snapshot(JNIEnv* env, RimeSessionId id, bool handled) {
  RIME_STRUCT(RimeCommit, commit);
  std::string committed;
  if (api->get_commit(id, &commit)) {
    if (commit.text) committed = commit.text;
    api->free_commit(&commit);
  }
  RIME_STRUCT(RimeContext, context);
  bool has_context = api->get_context(id, &context);
  int count = has_context ? context.menu.num_candidates : 0;
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray candidates = env->NewObjectArray(count, string_class, nullptr);
  env->DeleteLocalRef(string_class);
  for (int i = 0; i < count; ++i) {
    jstring text = string(env, context.menu.candidates[i].text);
    env->SetObjectArrayElement(candidates, i, text);
    env->DeleteLocalRef(text);
  }
  jstring text = string(env, committed.c_str());
  jstring preedit = string(env, has_context ? context.composition.preedit : "");
  jclass cls = env->FindClass("juloo/keyboard2/input/EngineResult");
  jobject result = env->NewObject(cls, env->GetMethodID(cls, "<init>",
      "(ZLjava/lang/String;Ljava/lang/String;[Ljava/lang/String;IZZ)V"),
      static_cast<jboolean>(handled), text, preedit, candidates,
      has_context ? context.menu.highlighted_candidate_index : 0,
      static_cast<jboolean>(has_context && context.composition.length > 0),
      static_cast<jboolean>(!has_context || context.menu.is_last_page));
  if (has_context) api->free_context(&context);
  env->DeleteLocalRef(cls);
  env->DeleteLocalRef(text);
  env->DeleteLocalRef(preedit);
  env->DeleteLocalRef(candidates);
  return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_juloo_keyboard2_input_RimeEngine_open(JNIEnv* env, jclass, jstring shared, jstring user) {
  std::lock_guard<std::mutex> lock(mutex);
  const char* s = env->GetStringUTFChars(shared, nullptr);
  const char* u = env->GetStringUTFChars(user, nullptr);
  std::string shared_value(s), user_value(u);
  env->ReleaseStringUTFChars(shared, s);
  env->ReleaseStringUTFChars(user, u);
  if (sessions.empty()) {
    shared_path = shared_value;
    user_path = user_value;
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_path.c_str();
    traits.user_data_dir = user_path.c_str();
    traits.distribution_name = "MIANCAK";
    traits.distribution_code_name = "miancak";
    traits.distribution_version = "0.1.0";
    traits.app_name = "rime.miancak";
    api->setup(&traits);
    api->initialize(&traits);
    if (api->start_maintenance(true)) api->join_maintenance_thread();
  } else if (shared_path != shared_value || user_path != user_value) {
    fail(env, "Rime runtime already uses another data directory");
    return 0;
  }
  RimeSessionId id = api->create_session();
  if (!id || !api->select_schema(id, "miancak_pinyin_private")) {
    if (id) api->destroy_session(id);
    if (sessions.empty()) { api->finalize(); api = nullptr; }
    fail(env, "Offline Chinese schema deployment failed");
    return 0;
  }
  api->set_option(id, "ascii_mode", false);
  sessions.insert(id);
  return static_cast<jlong>(id);
}

extern "C" JNIEXPORT jobject JNICALL
Java_juloo_keyboard2_input_RimeEngine_key(JNIEnv* env, jclass, jlong id, jint code) {
  std::lock_guard<std::mutex> lock(mutex);
  if (!valid(env, id)) return nullptr;
  bool handled = api->process_key(id, code, 0);
  return snapshot(env, id, handled);
}

extern "C" JNIEXPORT jobject JNICALL
Java_juloo_keyboard2_input_RimeEngine_command(JNIEnv* env, jclass, jlong id, jint cmd, jint arg) {
  std::lock_guard<std::mutex> lock(mutex);
  if (!valid(env, id)) return nullptr;
  bool handled = true;
  switch (cmd) {
    case 0: handled = api->commit_composition(id); break;
    case 1: api->clear_composition(id); break;
    case 2: handled = arg >= 0 && api->select_candidate_on_current_page(id, arg); break;
    case 3: handled = api->change_page(id, arg != 0); break;
    default: fail(env, "Invalid Rime command"); return nullptr;
  }
  return snapshot(env, id, handled);
}

extern "C" JNIEXPORT void JNICALL
Java_juloo_keyboard2_input_RimeEngine_learning(JNIEnv* env, jclass, jlong id, jboolean enabled) {
  std::lock_guard<std::mutex> lock(mutex);
  if (valid(env, id) && !api->select_schema(id,
        enabled ? "miancak_pinyin" : "miancak_pinyin_private"))
    fail(env, "Cannot select the requested learning policy");
}

extern "C" JNIEXPORT void JNICALL
Java_juloo_keyboard2_input_RimeEngine_destroy(JNIEnv*, jclass, jlong id) {
  std::lock_guard<std::mutex> lock(mutex);
  if (!api || !sessions.erase(id)) return;
  api->destroy_session(id);
  if (sessions.empty()) { api->finalize(); api = nullptr; }
}
