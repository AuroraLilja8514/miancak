#ifndef MIANCAK_WANXIANG_RUNTIME_H
#define MIANCAK_WANXIANG_RUNTIME_H

#include <fstream>
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

inline bool schemas_ready(RimeApi* api) {
  for (const char* name : {"miancak_pinyin", "miancak_pinyin_private"}) {
    RimeConfig config = {};
    if (!api->schema_open(name, &config)) return false;
    char dictionary[256] = {}, alphabet[256] = {};
    bool ready = api->config_get_string(&config, "translator/dictionary", dictionary, sizeof(dictionary)) && dictionary[0]
      && api->config_get_string(&config, "speller/alphabet", alphabet, sizeof(alphabet)) && alphabet[0];
    if (std::string(name) == "miancak_pinyin_private") {
      Bool learning = True;
      ready = ready && api->config_get_bool(&config, "translator/enable_user_dict", &learning) && !learning;
    }
    api->config_close(&config);
    if (!ready) return false;
  }
  return true;
}
}  // namespace miancak
#endif
