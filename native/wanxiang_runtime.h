#ifndef MIANCAK_WANXIANG_RUNTIME_H
#define MIANCAK_WANXIANG_RUNTIME_H

#include <fstream>
#include <initializer_list>
#include <rime_api.h>
#include <octagram.h>

namespace miancak {
inline const char* runtime_modules[] = {"default", "octagram", "lua", nullptr};

inline bool wanxiang_ready(RimeApi* api, const std::string& shared) {
  if (!std::ifstream(shared + "/miancak-profile.json").good())
    return true;  // Existing/custom data-only deployments retain their schema.
  if (!api->find_module("octagram") || !api->find_module("lua"))
    return false;
  auto* grammar = dynamic_cast<rime::OctagramComponent*>(rime::Grammar::Require("grammar"));
  // Require a real, readable grammar database. Octagram otherwise continues
  // without its model, which must not be reported as a Base+grammar runtime.
  return grammar && grammar->GetDb("wanxiang-lts-zh-hans");
}

inline bool fixed_list(RimeApi* api, RimeConfig* config, const char* key, std::initializer_list<const char*> values) {
  if (api->config_list_size(config, key) != values.size()) return false;
  int index = 0;
  for (const char* value : values) {
    char actual[256] = {}; std::string path = std::string(key) + "/@" + std::to_string(index++);
    if (!api->config_get_string(config, path.c_str(), actual, sizeof(actual)) || std::string(actual) != value) return false;
  }
  return true;
}

inline bool schemas_ready(RimeApi* api) {
  bool base = false;
  std::string normal_dictionary;
  for (const char* name : {"miancak_pinyin", "miancak_pinyin_private"}) {
    RimeConfig config = {};
    if (!api->schema_open(name, &config)) return false;
    char dictionary[256] = {}, alphabet[256] = {}, id[256] = {};
    bool ready = api->config_get_string(&config, "translator/dictionary", dictionary, sizeof(dictionary)) && dictionary[0]
      && api->config_get_string(&config, "speller/alphabet", alphabet, sizeof(alphabet)) && alphabet[0]
      && api->config_get_string(&config, "schema/schema_id", id, sizeof(id)) && std::string(id) == name;
    ready = ready && (std::string(dictionary) == "pinyin_simp" || std::string(dictionary) == "wanxiang" || std::string(dictionary) == "miancak_wanxiang");
    if (std::string(name) == "miancak_pinyin") normal_dictionary = dictionary;
    else ready = ready && normal_dictionary == dictionary;
    base = base || std::string(dictionary) != "pinyin_simp";
    if (std::string(dictionary) != "pinyin_simp") {
      char language[128] = {};
      ready = ready && api->config_get_string(&config, "grammar/language", language, sizeof(language)) && std::string(language) == "wanxiang-lts-zh-hans";
    }
    if (std::string(name) == "miancak_pinyin_private") {
      Bool learning = True;
      ready = ready && api->config_get_bool(&config, "translator/enable_user_dict", &learning) && !learning;
      ready = ready && fixed_list(api, &config, "engine/processors", {"speller", "selector", "navigator", "express_editor"})
        && fixed_list(api, &config, "engine/segmentors", {"abc_segmentor", "fallback_segmentor"})
        && fixed_list(api, &config, "engine/translators", {"script_translator"});
      if (api->config_list_size(&config, "engine/filters")) ready = ready && fixed_list(api, &config, "engine/filters", {"uniquifier"});
    }
    api->config_close(&config);
    if (!ready) return false;
  }
  RimeConfig defaults = {};
  if (!api->config_open("default", &defaults)) return false;
  char first[128] = {}; Bool fixed = False;
  bool ready = api->config_get_string(&defaults, "schema_list/@0/schema", first, sizeof(first)) && std::string(first) == "miancak_pinyin_private";
  if (base) ready = ready && api->config_get_bool(&defaults, "switcher/fix_schema_list_order", &fixed) && fixed;
  api->config_close(&defaults); return ready;
}
}  // namespace miancak
#endif
