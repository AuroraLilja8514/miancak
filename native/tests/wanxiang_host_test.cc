// Actual pinned librime/plugins and real model/data on the host. This is not an
// Android/JNI/editor test and must not be reported as Android acceptance.
#include "../wanxiang_runtime.h"
#include <filesystem>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>
#include <algorithm>

namespace fs = std::filesystem;
using Snapshot = std::map<std::string, std::string>;
static RimeApi* api;
static int checks;
static const char* phase = "setup";

static void require(bool result, const char* name) {
  if (!result) throw std::runtime_error(name);
  ++checks;
}

static Snapshot learning(const fs::path& user) {
  Snapshot result;
  if (!fs::exists(user)) return result;
  for (const auto& entry : fs::recursive_directory_iterator(user)) {
    if (!entry.is_regular_file()) continue;
    auto path = entry.path().lexically_relative(user).generic_string();
    if (path.find(".userdb/") == std::string::npos) continue;
    std::ifstream input(entry.path(), std::ios::binary);
    result[path] = std::string(std::istreambuf_iterator<char>(input), {});
  }
  return result;
}

static void initialize(const fs::path& shared, const fs::path& user) {
  fs::create_directories(user);
  fs::create_directories(shared / "build");
  static std::string shared_name, user_name, build_name, prebuilt_name;
  shared_name = shared.string(); user_name = user.string();
  build_name = (shared / "build").string(); prebuilt_name = (shared / "prebuilt").string();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_name.c_str(); traits.user_data_dir = user_name.c_str();
  traits.staging_dir = build_name.c_str(); traits.prebuilt_data_dir = prebuilt_name.c_str();
  traits.distribution_name = "MIANCAK host verification";
  traits.distribution_code_name = "miancak"; traits.distribution_version = "wanxiang-base-host-test";
  traits.app_name = "rime.miancak"; traits.modules = miancak::runtime_modules;
  api->setup(&traits); api->initialize(&traits);
}

static void deploy(const fs::path& shared) {
  if (api->start_maintenance(true)) api->join_maintenance_thread();
  require(api->deploy_schema((shared / "miancak_pinyin.schema.yaml").c_str()), "normal schema deployment");
  require(api->deploy_schema((shared / "miancak_pinyin_private.schema.yaml").c_str()), "private schema deployment");
  require(miancak::schemas_ready(api), "compiled dictionary/alphabet/private policy");
}

static std::vector<std::string> input(RimeSessionId session, const char* text) {
  // Match the shipped JNI open path, which explicitly disables ASCII mode.
  api->set_option(session, "ascii_mode", false);
  for (auto* p = text; *p; ++p) require(api->process_key(session, *p, 0), "engine accepted synthetic input");
  RIME_STRUCT(RimeContext, context);
  require(api->get_context(session, &context), "engine context");
  std::vector<std::string> candidates;
  for (int i = 0; i < context.menu.num_candidates; ++i)
    candidates.emplace_back(context.menu.candidates[i].text);
  api->free_context(&context);
  require(!candidates.empty(), "real candidates");
  return candidates;
}

static std::string confirm(RimeSessionId session) {
  std::string text;
  for (int i = 0; i < 256; ++i) {
    RIME_STRUCT(RimeContext, context);
    bool composing = api->get_context(session, &context);
    bool active = composing && context.composition.length > 0;
    if (composing) api->free_context(&context);
    if (!active) { api->process_key(session, 0xffffff, 0); return text; }
    require(api->select_candidate_on_current_page(session, 0), "candidate confirmation");
    RIME_STRUCT(RimeCommit, commit);
    if (api->get_commit(session, &commit)) {
      if (commit.text) text += commit.text;
      api->free_commit(&commit);
    }
  }
  throw std::runtime_error("confirmation bound");
}

int main(int argc, char** argv) {
  try {
    require(argc == 4 || (argc == 5 && std::string(argv[4]) == "supplement"), "arguments: new-shared legacy-shared scratch [supplement]");
    fs::path shared = fs::absolute(argv[1]), legacy = fs::absolute(argv[2]), scratch = fs::absolute(argv[3]);
    require(!fs::exists(scratch), "fresh synthetic host directory");
    fs::create_directories(scratch);
    api = rime_get_api();
    fs::path user = scratch / "user";
    if (argc == 5) {
      // A distinct regression for Rime's nonrecursive import_tables. Use the
      // actual generated header consumed by WordTableStore, with a validated
      // synthetic untoned row. This does not test Android's file picker/store.
      phase = "Base supplement";
      fs::path selected = scratch / "selected"; fs::create_directories(selected);
      for (auto it = fs::recursive_directory_iterator(shared); it != fs::recursive_directory_iterator(); ++it) {
        auto relative = it->path().lexically_relative(shared);
        if (*relative.begin() == "build" || *relative.begin() == "prebuilt") {
          if (it->is_directory()) it.disable_recursion_pending();
          continue;
        }
        if (!it->is_regular_file()) continue;
        auto target = selected / relative; fs::create_directories(target.parent_path());
        if (it->path().extension() == ".gram") fs::create_hard_link(it->path(), target);
        else fs::copy_file(it->path(), target);
      }
      fs::copy_file(shared.parent_path() / "word-pack/wanxiang-custom.dict.yaml", selected / "miancak_wanxiang.dict.yaml");
      std::ofstream(selected / "miancak_custom.dict.yaml") << "---\nname: miancak_custom\nversion: 'host-test'\nsort: by_weight\nuse_preset_vocabulary: false\n...\nMIANCAK·附加词表\tce shi\t1000000000\n";
      std::ofstream(selected / "miancak_pinyin.schema.yaml", std::ios::app)
        << "  translator/dictionary: miancak_wanxiang\n  translator/prism: miancak_wanxiang\n";
      initialize(selected, user); require(miancak::wanxiang_ready(api, selected.string()), "supplement real grammar"); deploy(selected);
      auto session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin_private"), "supplement private schema");
      auto base = input(session, "nihao"); require(base.front() == "你好", "Base tables retained with supplement");
      require(confirm(session) == "你好", "Base commit with supplement");
      auto added = input(session, "ceshi"); auto found = std::find(added.begin(), added.end(), "MIANCAK·附加词表");
      require(found != added.end(), "untoned custom row remains selectable");
      require(api->select_candidate_on_current_page(session, found - added.begin()), "select custom candidate");
      RIME_STRUCT(RimeCommit, commit);
      require(api->get_commit(session, &commit), "custom commit");
      require(commit.text && std::string(commit.text) == "MIANCAK·附加词表", "exact custom text"); api->free_commit(&commit);
      api->destroy_session(session); api->finalize();
      require(learning(user).empty(), "supplement private input has no learning DB");
      std::cout << "{\"scope\":\"actual host Base and supplement; not Android store/SAF\",\"checks\":" << checks
                << ",\"base_retained\":true,\"custom_commit\":true,\"grammar_loaded\":true,\"private_no_learning\":true}\n";
      return 0;
    }
    // First produce genuine learning using the previous shipped dictionary and
    // fixed schema. Its database bytes must survive the Base switch unchanged.
    phase = "previous dictionary";
    initialize(legacy, user); deploy(legacy);
    auto session = api->create_session();
    require(session && api->select_schema(session, "miancak_pinyin"), "previous learning schema");
    input(session, "ni'hao'ni'hao"); require(!confirm(session).empty(), "previous learned commit");
    api->destroy_session(session); api->finalize();
    Snapshot before = learning(user); require(!before.empty(), "real previous learning database");

    phase = "private Base"; initialize(shared, user);
    require(miancak::wanxiang_ready(api, shared.string()), "actual Octagram model load");
    deploy(shared);
    session = api->create_session();
    require(session && api->select_schema(session, "miancak_pinyin_private"), "private Base schema");
    auto nihao = input(session, "nihao"); require(nihao.front() == "你好", "Base nihao output");
    require(confirm(session) == nihao.front(), "private confirmation matches shown candidate");
    input(session, "zhongguo"); require(!confirm(session).empty(), "private second composition");
    api->destroy_session(session); api->finalize();
    require(learning(user) == before, "private input neither creates nor changes learning DBs");

    phase = "normal Base"; initialize(shared, user); require(miancak::wanxiang_ready(api, shared.string()), "model reinitialization");
    session = api->create_session();
    require(session && api->select_schema(session, "miancak_pinyin"), "normal full Base Lua schema");
    input(session, "nihaoshijie"); require(!confirm(session).empty(), "full Base commit");
    api->destroy_session(session); api->finalize();
    Snapshot after = learning(user);
    for (const auto& old : before) require(after.count(old.first) && after.at(old.first) == old.second, "previous learning bytes retained");
    bool learned = false;
    for (const auto& entry : after) if (entry.first.find("wanxiang.userdb/") != std::string::npos) learned = true;
    require(learned, "separate genuine Wanxiang learning database");

    fs::path absent = scratch / "missing-model"; fs::create_directories(absent);
    std::ofstream(absent / "miancak-profile.json") << "{}\n";
    phase = "missing model"; initialize(absent, scratch / "missing-model-user");
    require(!miancak::wanxiang_ready(api, absent.string()), "missing model is a failure, not silent fallback");
    api->finalize();
    std::cout << "{\"scope\":\"actual host librime; not Android/editor acceptance\",\"checks\":" << checks
              << ",\"grammar_loaded\":true,\"private_learning_unchanged\":true,\"previous_learning_preserved\":true,\"missing_model_refused\":true}\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "HOST_RIME_FAILED: phase=" << phase << "; completed_checks=" << checks << "; " << error.what() << "\n";
    if (api) api->finalize();
    return 1;
  }
}
