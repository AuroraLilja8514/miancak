// Actual pinned librime/plugins and real model/data on the host. This is not an
// Android/JNI/editor test and must not be reported as Android acceptance.
#include "../wanxiang_runtime.h"
#include "../custom_config.h"
#include "../learning_recovery.h"
#include <filesystem>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>
#include <algorithm>
#include <rime/dict/db.h>
#include <rime/dict/user_db.h>
#include <regex>

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

static std::string read_text(const fs::path& file) {
  std::ifstream input(file, std::ios::binary);
  if (!input) throw std::runtime_error("cannot read synthetic fixture");
  return std::string(std::istreambuf_iterator<char>(input), {});
}
static void copy_shared(const fs::path& source, const fs::path& selected) {
  fs::create_directories(selected);
  for (auto it = fs::recursive_directory_iterator(source); it != fs::recursive_directory_iterator(); ++it) {
    auto relative = it->path().lexically_relative(source);
    if (*relative.begin() == "build" || *relative.begin() == "prebuilt") {
      if (it->is_directory()) it.disable_recursion_pending();
      continue;
    }
    if (!it->is_regular_file()) continue;
    auto target = selected / relative; fs::create_directories(target.parent_path());
    if (it->path().extension() == ".gram") fs::create_hard_link(it->path(), target);
    else fs::copy_file(it->path(), target);
  }
}

static void audit_normal(const fs::path& shared) {
  auto original = YAML::Load(read_text(shared / "wanxiang.schema.yaml"));
  RimeConfig compiled = {}; require(api->schema_open("miancak_pinyin", &compiled), "Base audit normal schema");
  for (const char* group : {"processors", "segmentors", "translators", "filters"}) {
    std::string key = std::string("engine/") + group;
    require(api->config_list_size(&compiled, key.c_str()) == original["engine"][group].size(), "complete Base component count");
    int index = 0;
    for (const auto& value : original["engine"][group]) {
      char actual[256] = {}; std::string path = key + "/@" + std::to_string(index++);
      require(api->config_get_string(&compiled, path.c_str(), actual, sizeof(actual)) && std::string(actual) == value.as<std::string>(), "exact stock normal Base component");
    }
  }
  char language[128] = {}; require(api->config_get_string(&compiled, "grammar/language", language, sizeof(language)) &&
    std::string(language) == original["grammar"]["language"].as<std::string>(), "exact Base grammar language");
  api->config_close(&compiled);
}

static void audit_spelling(const fs::path& selected, const char* name) {
  auto source = YAML::Load(read_text(selected / (std::string(name) + ".schema.yaml")));
  auto algebra = YAML::Load(read_text(selected / "wanxiang_algebra.yaml"));
  auto refs = std::string(name).find("miancak_") == 0 ? source["__patch"]["speller/algebra"]["__patch"] : source["speller"]["algebra"]["__patch"];
  std::vector<std::string> expected;
  for (const auto& ref : refs) {
    const std::string prefix = "wanxiang_algebra:/"; auto path = ref.as<std::string>();
    require(path.find(prefix) == 0, "dependent references use the pinned algebra file");
    std::istringstream parts(path.substr(prefix.size())); std::string part; YAML::Node fragment = YAML::Clone(algebra);
    while (std::getline(parts, part, '/')) fragment.reset(fragment[part]);
    require(fragment["__append"].IsSequence(), "pinned spelling fragment exists");
    for (const auto& rule : fragment["__append"]) expected.push_back(rule.as<std::string>());
  }
  auto compiled = YAML::Load(read_text(selected / "build" / (std::string(name) + ".schema.yaml")))["speller"]["algebra"];
  require(compiled.IsSequence() && compiled.size() == expected.size() && !expected.empty(), "compiled spelling rule count matches selected upstream fragments");
  for (size_t i = 0; i < expected.size(); ++i) require(compiled[i].as<std::string>() == expected[i], "compiled spelling rule exactly matches selected upstream fragment");
}

int main(int argc, char** argv) {
  try {
    require(argc == 4 || (argc == 5 && (std::string(argv[4]) == "supplement" || std::string(argv[4]) == "custom-config" || std::string(argv[4]) == "learning-recovery" || std::string(argv[4]) == "spelling")), "arguments: new-shared legacy-shared scratch [supplement|custom-config|learning-recovery|spelling]");
    fs::path shared = fs::absolute(argv[1]), legacy = fs::absolute(argv[2]), scratch = fs::absolute(argv[3]);
    require(!fs::exists(scratch), "fresh synthetic host directory");
    fs::create_directories(scratch);
    api = rime_get_api();
    fs::path user = scratch / "user";
    if (argc == 5 && std::string(argv[4]) == "learning-recovery") {
      phase = "learning recovery investigation";
      initialize(legacy, user); deploy(legacy);
      auto session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin"), "old learning session");
      input(session, "nihao"); require(!confirm(session).empty(), "genuine old learned commit"); api->destroy_session(session); api->finalize();
      // Add clearly synthetic positive and deletion fixtures through the real
      // old database implementation, then close everything before snapshot.
      initialize(legacy, user);
      std::unique_ptr<rime::Db> db(rime::UserDb::Require("userdb")->Create("pinyin_simp")); require(db->Open(), "old synthetic database open");
      rime::UserDbValue value; value.commits = 7; value.dee = 1e-7; value.tick = 1;
      require(db->Update("nve nve \tMIANCAK·旧学习短语", value.Pack()), "positive old learned row");
      value.commits = -1; require(db->Update("shan chu \t已删除测试", value.Pack()), "deleted old fixture");
      db->Close(); db.reset(); api->finalize(); Snapshot before = learning(user);
      require(!before.empty(), "closed original learning snapshot");
      fs::path copied_user = scratch / "copied-user", original = user / "pinyin_simp.userdb";
      fs::create_directories(copied_user); size_t files = 0; uintmax_t bytes = 0;
      for (const auto& entry : fs::recursive_directory_iterator(original)) {
        require(!entry.is_symlink(), "no links in copied database");
        if (entry.is_regular_file()) { ++files; bytes += entry.file_size(); }
      }
      require(files <= 512 && bytes <= 64 * 1024 * 1024, "bounded closed copy");
      fs::path pristine = scratch / "pristine"; fs::create_directories(pristine);
      fs::copy(original, pristine / "pinyin_simp.userdb", fs::copy_options::recursive);
      fs::copy(pristine / "pinyin_simp.userdb", copied_user / "pinyin_simp.userdb", fs::copy_options::recursive);
      initialize(legacy, copied_user);
      auto counts = miancak::recover_copied_legacy((scratch / "recovered.dict.yaml").string(), "miancak_custom", 60000);
      std::string recovered = read_text(scratch / "recovered.dict.yaml"); api->finalize();
      require(counts.active >= 2 && counts.deleted == 1 && counts.records == counts.active + counts.deleted, "all active/deleted records accounted for");
      require(counts.unsupported == 0, "supported original export has no omitted records");
      require(learning(user) == before, "export never opens or changes original learning files");
      require(learning(pristine) == before, "pristine snapshot is separate and unchanged");
      fs::path selected = scratch / "selected"; copy_shared(shared, selected);
      fs::copy_file(shared.parent_path() / "word-pack/wanxiang-custom.dict.yaml", selected / "miancak_wanxiang.dict.yaml");
      std::ofstream(selected / "miancak_custom.dict.yaml") << recovered;
      std::ofstream(selected / "miancak_pinyin.schema.yaml", std::ios::app) << "  translator/dictionary: miancak_wanxiang\n  translator/prism: miancak_wanxiang\n";
      initialize(selected, user); require(miancak::wanxiang_ready(api, selected.string()), "recovered supplement real model"); deploy(selected);
      session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin_private"), "recovered private session");
      input(session, "nvenve");
      RimeCandidateListIterator iterator = {}; size_t index = 0; bool found = false;
      require(api->candidate_list_begin(session, &iterator), "recovered candidate iteration");
      while (index < 4000 && api->candidate_list_next(&iterator)) {
        if (std::string(iterator.candidate.text) == "MIANCAK·旧学习短语") { found = true; break; } ++index;
      }
      api->candidate_list_end(&iterator); require(found, "old untoned phrase selectable from copied supplement");
      require(api->select_candidate(session, index), "select recovered phrase");
      RIME_STRUCT(RimeCommit, commit); require(api->get_commit(session, &commit), "recovered phrase commit");
      require(commit.text && std::string(commit.text) == "MIANCAK·旧学习短语", "exact recovered phrase"); api->free_commit(&commit);
      api->destroy_session(session); api->finalize(); require(learning(user) == before, "Base private recovery leaves original learning bytes intact");
      // A mixed/unknown encoding on the copy must refuse the entire proposed
      // export, never silently omit it or alter the original or active data.
      initialize(legacy, copied_user); db.reset(rime::UserDb::Require("userdb")->Create("pinyin_simp")); require(db->Open(), "copied database for negative fixture");
      value.commits = 1; require(db->Update("nǐ hǎo \t未知编码测试", value.Pack()), "unsupported copied-code fixture"); db->Close(); db.reset();
      auto bad = miancak::recover_copied_legacy((scratch / "blocked.dict.yaml").string(), "miancak_custom", 60000);
      api->finalize(); require(bad.unsupported == 1 && bad.records == bad.active + bad.deleted + bad.unsupported, "unsupported copied codes are explicitly accounted for");
      require(!fs::exists(scratch / "blocked.dict.yaml") && !fs::exists(scratch / "blocked.dict.yaml.partial"), "unsupported rows refuse the entire output");
      require(learning(user) == before && learning(pristine) == before, "refusal retains original and pristine bytes");
      int commits = 0;
      for (const char* packed : {"c=1", "c=1 d=nan t=1", "c=1 d=-1 t=1", "c=1 d=0 t=-1", "c=1 d=0 t=1 x=1", "c=1 c=2 d=0 t=1", "c=1.5 d=0 t=1", "c=2147483648 d=0 t=1"})
        require(!miancak::learned_count(packed, commits), "unsupported packed value is refused");
      require(miancak::learned_count("c=0 d=0.00000001 t=1", commits) && commits == 0, "supported zero count remains active with weight one");
      require(!miancak::learned_text(std::string("bad\xc0\xaf")) && !miancak::learned_text(std::string("bad\xc2\x85")), "invalid UTF-8 and C1 controls refused");
      std::cout << "{\"scope\":\"production recovery exporter and real host Base; not Android migration UI\",\"checks\":" << checks
                << ",\"active_records\":" << counts.active << ",\"deleted_records_retained_in_original\":" << counts.deleted
                << ",\"original_bytes_unchanged\":true,\"recovered_phrase_committed\":true,\"unsupported_codes_refused\":true}\n";
      return 0;
    }
    if (argc == 5 && std::string(argv[4]) == "spelling") {
      phase = "pinned spelling selectors";
      const std::vector<std::pair<std::string, std::string>> modes = {{"全拼", "nihao"}, {"小鹤双拼", "nihc"}, {"自然码", "nihk"}, {"微软双拼", "nihk"}};
      for (size_t index = 0; index < modes.size(); ++index) {
        const auto& mode = modes[index]; fs::path selected = scratch / ("selected-" + std::to_string(index)); copy_shared(shared, selected);
        std::string patch = "patch:\n  speller:\n    algebra:\n      __patch: [wanxiang_algebra:/base/" + mode.first + ", wanxiang_algebra:/26jian]\n  translator: {enable_user_dict: false, contextual_suggestions: true, core_word_length: 0, max_word_length: 12}\n  super_processor/enable_backspace_limit: false\n";
        for (const char* name : {"miancak_pinyin", "miancak_pinyin_private", "wanxiang_mixedcode", "wanxiang_english", "wanxiang_reverse"}) {
          fs::path file = selected / (std::string(name) + ".schema.yaml");
          auto before = read_text(file); std::ofstream(file) << miancak::merge_custom(before, "wanxiang.custom.yaml", patch);
        }
        fs::path mode_user = scratch / ("user-" + std::to_string(index));
        initialize(selected, mode_user); require(miancak::wanxiang_ready(api, selected.string()), "spelling uses real Base grammar"); deploy(selected); audit_normal(selected);
        RimeConfig normal = {}, priv = {};
        require(api->schema_open("miancak_pinyin", &normal) && api->schema_open("miancak_pinyin_private", &priv), "spelling compiled schemas");
        Bool value = True; int length = -1;
        require(api->config_get_bool(&normal, "translator/enable_user_dict", &value) && !value, "normal user-learning preference");
        require(api->config_get_bool(&priv, "translator/enable_user_dict", &value) && !value, "private always forbids user learning");
        require(api->config_get_bool(&normal, "translator/contextual_suggestions", &value) && value, "normal context preference");
        require(api->config_get_bool(&priv, "translator/contextual_suggestions", &value) && !value, "private does not inherit normal context preference");
        require(api->config_get_int(&normal, "translator/max_word_length", &length) && length == 12, "normal word length preference");
        require(api->config_get_bool(&normal, "super_processor/enable_backspace_limit", &value) && !value, "normal Base processor preference");
        api->config_close(&normal); api->config_close(&priv);
        auto session = miancak::create_private_session(api); require(session && api->select_schema(session, "miancak_pinyin_private"), "spelling private session");
        auto values = input(session, mode.second.c_str()); require(std::find(values.begin(), values.end(), "你好") != values.end(), "correct encoded greeting in real private candidates");
        api->clear_composition(session); input(session, "ni"); api->clear_composition(session);
        api->destroy_session(session); api->finalize(); require(learning(mode_user).empty(), "custom spelling private path has no learning writes");
        initialize(selected, mode_user); session = miancak::create_private_session(api); require(session && api->select_schema(session, "miancak_pinyin"), "spelling normal session");
        values = input(session, mode.second.c_str()); require(std::find(values.begin(), values.end(), "你好") != values.end(), "correct encoded greeting in real full normal candidates");
        api->clear_composition(session); api->destroy_session(session); api->finalize();
        for (const char* name : {"miancak_pinyin", "miancak_pinyin_private", "wanxiang_mixedcode", "wanxiang_english", "wanxiang_reverse"}) audit_spelling(selected, name);
      }
      std::cout << "{\"scope\":\"four pinned Base spelling selectors, dependent compilation and real host normal/private input; not Android\",\"checks\":" << checks << ",\"modes\":4,\"private_no_learning\":true,\"stock_normal_components\":true}\n";
      return 0;
    }
    if (argc == 5 && std::string(argv[4]) == "custom-config") {
      phase = "custom configuration";
      fs::path selected = scratch / "selected"; copy_shared(shared, selected);
      const std::string patch = "patch:\n  menu: {page_size: 3}\n  translator: {enable_completion: false, enable_word_completion: false, max_homophones: 4}\n  'switches/@7/reset': 1\n";
      auto original_default = read_text(selected / "default.yaml");
      auto original_normal = read_text(selected / "miancak_pinyin.schema.yaml");
      auto original_private = read_text(selected / "miancak_pinyin_private.schema.yaml");
      const std::string default_patch = "patch: {'menu/page_size': 5}\n";
      std::ofstream(selected / "default.yaml") << miancak::merge_custom(original_default, "default.custom.yaml", default_patch);
      auto configured = miancak::merge_custom(original_normal, "default.custom.yaml", default_patch);
      std::ofstream(selected / "miancak_pinyin.schema.yaml") << miancak::merge_custom(configured, "wanxiang.custom.yaml", patch);
      configured = miancak::merge_custom(original_private, "default.custom.yaml", default_patch);
      std::ofstream(selected / "miancak_pinyin_private.schema.yaml") << miancak::merge_custom(configured, "wanxiang.custom.yaml", patch);
      initialize(selected, user); require(miancak::wanxiang_ready(api, selected.string()), "configured real grammar"); deploy(selected);
      for (const auto& name : {"miancak_pinyin", "miancak_pinyin_private"}) {
        RimeConfig config = {}; require(api->schema_open(name, &config), "configured schema open"); int size = 0;
        require(api->config_get_int(&config, "menu/page_size", &size) && size == 3, "patch page-size compiled into both schemas");
        Bool completion = True;
        require(api->config_get_bool(&config, "translator/enable_completion", &completion) && !completion, "configured completion flag");
        require(api->config_get_bool(&config, "translator/enable_word_completion", &completion) && !completion, "configured word-completion flag");
        require(api->config_get_int(&config, "translator/max_homophones", &size) && size == 4, "configured homophone bound"); api->config_close(&config);
      }
      auto session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin_private"), "configured private session");
      auto values = input(session, "shi"); require(values.size() == 3, "actual private candidate page follows custom size"); require(!confirm(session).empty(), "configured private commit");
      api->destroy_session(session); api->finalize(); require(learning(user).empty(), "configuration does not enable private learning");
      initialize(selected, user); session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin"), "configured normal session");
      values = input(session, "zhongguo"); require(values.front() == "中國", "normal Base traditional reset takes real effect"); require(confirm(session) == "中國", "configured traditional commit");
      api->destroy_session(session); api->finalize();
      // Corrupt only this synthetic staged schema. Production readiness must
      // reject an executable private pipeline before a session can exist.
      auto bad = YAML::Load(original_private);
      bad["__patch"]["engine/processors"] = YAML::Load("[lua_processor@*wanxiang.input_statistics, speller]");
      std::ofstream(selected / "miancak_pinyin_private.schema.yaml") << bad;
      initialize(selected, scratch / "guard-user");
      if (api->start_maintenance(true)) api->join_maintenance_thread();
      require(api->deploy_schema((selected / "miancak_pinyin_private.schema.yaml").c_str()), "synthetic unsafe schema compiled");
      require(!miancak::schemas_ready(api), "compiled private component policy refuses Lua injection"); api->finalize();
      require(learning(scratch / "guard-user").empty(), "refused schema never instantiated learning components");
      std::ofstream(selected / "miancak_pinyin_private.schema.yaml") << original_private;
      bad = YAML::Load(original_default); bad["__patch"]["schema_list"] = YAML::Load("[{schema: miancak_pinyin}, {schema: miancak_pinyin_private}]");
      std::ofstream(selected / "default.yaml") << bad;
      initialize(selected, scratch / "order-guard-user");
      if (api->start_maintenance(true)) api->join_maintenance_thread();
      require(api->deploy_schema((selected / "miancak_pinyin_private.schema.yaml").c_str()), "restored private schema compiled");
      require(!miancak::schemas_ready(api), "compiled defaults refuse normal-first initialization"); api->finalize();
      require(read_text(shared / "miancak_pinyin.schema.yaml") == original_normal, "original bundled schema retained");
      fs::path custom_base = scratch / "legacy-selected"; copy_shared(legacy, custom_base);
      for (const auto& file : {"default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml"}) {
        auto before = read_text(custom_base / file);
        std::ofstream(custom_base / file) << miancak::merge_custom(before, "default.custom.yaml", "patch: {menu/page_size: 4}");
      }
      initialize(custom_base, scratch / "legacy-user"); deploy(custom_base);
      session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin_private"), "configured data-only private session");
      values = input(session, "shi"); require(values.size() == 4, "custom base retains effective private page-size preference");
      require(!confirm(session).empty(), "configured data-only private commit"); api->destroy_session(session); api->finalize();
      require(learning(scratch / "legacy-user").empty(), "configured custom base remains non-learning");
      // Replay a retained old deployment with a remembered normal schema.
      // The new helper must cover it without rewriting its default source.
      auto old_default = read_text(custom_base / "default.yaml");
      auto remember_normal = [&]() {
        initialize(custom_base, scratch / "legacy-remembered");
        auto s = api->create_session(); require(s && api->select_schema(s, "miancak_pinyin"), "remember old normal schema");
        input(s, "nihao"); require(!confirm(s).empty(), "old normal learned commit"); api->destroy_session(s); api->finalize();
      };
      remember_normal(); Snapshot previous = learning(scratch / "legacy-remembered");
      initialize(custom_base, scratch / "legacy-remembered");
      session = api->create_session(); require(session && api->select_schema(session, "miancak_pinyin_private"), "old startup control");
      input(session, "nihao"); require(!confirm(session).empty(), "old startup control commit"); api->destroy_session(session); api->finalize();
      bool old_startup_changed_db = learning(scratch / "legacy-remembered") != previous;
      remember_normal(); previous = learning(scratch / "legacy-remembered");
      initialize(custom_base, scratch / "legacy-remembered");
      session = miancak::create_private_session(api); require(session && api->select_schema(session, "miancak_pinyin_private"), "retained profile starts private");
      input(session, "nihao"); require(!confirm(session).empty(), "retained private commit"); api->destroy_session(session); api->finalize();
      require(learning(scratch / "legacy-remembered") == previous, "remembered old normal cannot open learning DB during private startup");
      require(read_text(custom_base / "default.yaml") == old_default, "old default source remains byte-for-byte unchanged");
      std::cout << "{\"scope\":\"actual host Base custom configuration; not Android UI/JNI acceptance\",\"checks\":" << checks
                << ",\"compiled_and_visible_effect\":true,\"private_no_learning\":true,\"unsafe_private_and_order_refused\":true,\"old_startup_control_changed_db\":"
                << (old_startup_changed_db ? "true" : "false") << ",\"retained_private_startup_db_unchanged\":true}\n";
      return 0;
    }
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
    audit_normal(shared);
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
