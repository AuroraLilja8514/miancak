#include <jni.h>
#include <rime_api.h>
#include <mutex>
#include <map>
#include <vector>
#include <set>
#include <string>

namespace {
std::mutex mutex;
std::set<RimeSessionId> sessions;
std::map<RimeSessionId,int> candidate_bases;
std::string shared_path, user_path, build_path, prebuilt_path;
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

// Memory::OnCommit leaves an undoable user-dictionary transaction pending.
// Rime's unhandled-key notification commits it. VoidSymbol is sent only while
// idle, so it cannot confirm or change a composition or emit literal text.
void flush_idle_learning(RimeSessionId id) {
  RIME_STRUCT(RimeContext, context);
  bool found = api->get_context(id, &context);
  bool idle = !found || context.composition.length == 0;
  if (found) api->free_context(&context);
  if (idle) api->process_key(id, 0xffffff /* XK_VoidSymbol */, 0);
}

// Confirm the displayed selection before committing. A raw commit_composition
// can bypass the confirmed-segment notification used by Rime's memorizer.
bool confirm_selection(RimeSessionId id) {
  bool selected = false;
  for (int step = 0; step < 256; ++step) {
    RIME_STRUCT(RimeContext, context);
    bool found = api->get_context(id, &context);
    bool composing = found && context.composition.length > 0;
    int count = found ? context.menu.num_candidates : 0;
    int index = found ? context.menu.highlighted_candidate_index : 0;
    if (found) api->free_context(&context);
    if (!composing) return selected || api->commit_composition(id);
    if (count == 0) return api->commit_composition(id);
    if (index < 0 || index >= count) index = 0;
    if (!api->select_candidate_on_current_page(id, index)) return false;
    selected = true;
  }
  return false;
}

jobject snapshot(JNIEnv* env, RimeSessionId id, bool handled, int prefix_limit=0) {
  RIME_STRUCT(RimeCommit, commit);
  std::string committed;
  if (api->get_commit(id, &commit)) {
    if (commit.text) committed = commit.text;
    api->free_commit(&commit);
  }
  if (!committed.empty()) flush_idle_learning(id);
  RIME_STRUCT(RimeContext, context);
  bool has_context = api->get_context(id, &context);
  std::vector<std::string> values;
  bool last_page=!has_context || context.menu.is_last_page;
  int base=has_context?context.menu.page_no*context.menu.page_size:0;
  int highlighted=has_context?context.menu.highlighted_candidate_index:0;
  if(prefix_limit>0 && has_context && context.composition.length>0) {
    // Browse lazily, leaving ordinary key strokes at the cheap first menu.
    RimeCandidateListIterator iterator={};last_page=true;
    if(api->candidate_list_begin(id,&iterator)) {
      while(api->candidate_list_next(&iterator)) {
        if(static_cast<int>(values.size())==prefix_limit){last_page=false;break;}
        values.emplace_back(iterator.candidate.text?iterator.candidate.text:"");
      }
      api->candidate_list_end(&iterator);
    }
    highlighted+=base;base=0;
  } else if(has_context) {
    for(int i=0;i<context.menu.num_candidates;i++)values.emplace_back(context.menu.candidates[i].text?context.menu.candidates[i].text:"");
  }
  candidate_bases[id]=base;
  jclass string_class=env->FindClass("java/lang/String");
  jobjectArray candidates=env->NewObjectArray(values.size(),string_class,nullptr);env->DeleteLocalRef(string_class);
  for(size_t i=0;i<values.size();i++) {
    jstring value=string(env,values[i].c_str());env->SetObjectArrayElement(candidates,i,value);env->DeleteLocalRef(value);
  }
  jstring text = string(env, committed.c_str());
  jstring preedit = string(env, has_context ? context.composition.preedit : "");
  jclass cls = env->FindClass("juloo/keyboard2/input/EngineResult");
  jobject result = env->NewObject(cls, env->GetMethodID(cls, "<init>",
      "(ZLjava/lang/String;Ljava/lang/String;[Ljava/lang/String;IZZ)V"),
      static_cast<jboolean>(handled), text, preedit, candidates,
      highlighted,
      static_cast<jboolean>(has_context && context.composition.length > 0),
      static_cast<jboolean>(last_page));
  if (has_context) api->free_context(&context);
  env->DeleteLocalRef(cls);
  env->DeleteLocalRef(text);
  env->DeleteLocalRef(preedit);
  env->DeleteLocalRef(candidates);
  return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_juloo_keyboard2_input_RimeEngine_open(JNIEnv* env, jclass, jstring shared, jstring user, jstring build, jboolean verify) {
  std::lock_guard<std::mutex> lock(mutex);
  const char* s = env->GetStringUTFChars(shared, nullptr);
  const char* u = env->GetStringUTFChars(user, nullptr);
  const char* b = env->GetStringUTFChars(build, nullptr);
  std::string shared_value(s), user_value(u), build_value(b);
  env->ReleaseStringUTFChars(shared, s);
  env->ReleaseStringUTFChars(user, u);
  env->ReleaseStringUTFChars(build, b);
  if (sessions.empty()) {
    shared_path = shared_value;
    user_path = user_value;
    build_path = build_value;
    prebuilt_path = shared_path + "/prebuilt";
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_path.c_str();
    traits.user_data_dir = user_path.c_str();
    traits.distribution_name = "MIANCAK";
    traits.distribution_code_name = "miancak";
    traits.distribution_version = "0.3.0";
    traits.app_name = "rime.miancak";
    traits.staging_dir = build_path.c_str();
    traits.prebuilt_data_dir = prebuilt_path.c_str();
    api->setup(&traits);
    api->initialize(&traits);
    if (api->start_maintenance(true)) api->join_maintenance_thread();
    if (verify && (!api->deploy_schema((shared_path + "/miancak_pinyin.schema.yaml").c_str()) ||
                   !api->deploy_schema((shared_path + "/miancak_pinyin_private.schema.yaml").c_str()))) {
      api->finalize(); api = nullptr;
      fail(env, "Word table compilation failed / 词表编译失败");
      return 0;
    }
  } else if (shared_path != shared_value || user_path != user_value || build_path != build_value) {
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
    case 0: handled = confirm_selection(id); break;
    case 1: api->clear_composition(id); flush_idle_learning(id); break;
    case 2: handled = arg >= 0 && api->select_candidate(id,static_cast<size_t>(candidate_bases[id])+arg); break;
    case 3: handled = api->change_page(id, arg != 0); break;
    case 4: break; // Inspect without changing composition or re-emitting an old commit.
    case 5:
      if(arg<=0){fail(env,"Invalid candidate prefix length");return nullptr;}
      return snapshot(env,id,true,arg);
    default: fail(env, "Invalid Rime command"); return nullptr;
  }
  return snapshot(env, id, handled);
}

extern "C" JNIEXPORT void JNICALL
Java_juloo_keyboard2_input_RimeEngine_learning(JNIEnv* env, jclass, jlong id, jboolean enabled) {
  std::lock_guard<std::mutex> lock(mutex);
  if (!valid(env, id)) return;
  flush_idle_learning(id);
  if (!api->select_schema(id, enabled ? "miancak_pinyin" : "miancak_pinyin_private"))
    fail(env, "Cannot select the requested learning policy");
}

extern "C" JNIEXPORT void JNICALL
Java_juloo_keyboard2_input_RimeEngine_destroy(JNIEnv*, jclass, jlong id) {
  std::lock_guard<std::mutex> lock(mutex);
  if (!api || !sessions.count(id)) return;
  api->clear_composition(id);
  flush_idle_learning(id);
  sessions.erase(id);
  candidate_bases.erase(id);
  api->destroy_session(id);
  if (sessions.empty()) { api->finalize(); api = nullptr; }
}
