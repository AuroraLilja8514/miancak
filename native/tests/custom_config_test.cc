#include "../custom_config.h"
#include <iostream>
#include <vector>

int main() {
  int checks = 0;
  try {
    auto require = [&](bool value) { if (!value) throw std::runtime_error("custom-config assertion"); ++checks; };
    auto reject = [&](const std::string& filename, const std::string& text) {
      bool refused = false;
      try { miancak::canonical_custom(filename, text); } catch (const std::exception&) { refused = true; }
      require(refused);
    };
    auto nested = miancak::parse_custom("wanxiang.custom.yaml", "# retained separately\npatch:\n  menu: {page_size: 3}\n  translator:\n    enable_completion: false\n    max_homophones: 4\n  'switches/@7/reset': 1\n");
    require(nested.size() == 4); require(nested.at("menu/page_size").as<int>() == 3);
    auto canonical = miancak::canonical_custom("wanxiang.custom.yaml", "patch: {'menu/page_size': 3}\n");
    require(miancak::canonical_custom("wanxiang.custom.yaml", canonical) == canonical);
    auto merged = miancak::merge_custom("schema: {schema_id: miancak_pinyin}\n__include: wanxiang.schema:/\n__patch:\n  menu/page_size: 10\n  translator/user_dict: wanxiang\n", "wanxiang.custom.yaml", canonical);
    auto yaml = YAML::Load(merged); require(yaml["__patch"]["menu/page_size"].as<int>() == 3);
    require(yaml["__patch"]["translator/user_dict"].as<std::string>() == "wanxiang");
    require(yaml["schema"]["schema_id"].as<std::string>() == "miancak_pinyin");
    require(miancak::parse_custom("wanxiang.custom.yaml", "\xef\xbb\xbf# 中文注释\npatch: {menu/page_size: 5}").size() == 1);
    auto private_config = YAML::Load(miancak::merge_custom("schema: {schema_id: miancak_pinyin_private}\n__include: miancak_pinyin.schema:/\n__patch: {translator/enable_user_dict: false}\n", "wanxiang.custom.yaml", "patch: {menu/page_size: 3, switches/@7/reset: 1}"));
    require(!private_config["__patch"]["translator/enable_user_dict"].as<bool>());
    require(!private_config["__patch"]["switches/@7/reset"]);
    for (const auto& name : {"default.custom.yaml", "wanxiang.custom.yaml", "miancak_pinyin.custom.yaml"}) {
      require(miancak::parse_custom(name, "---\npatch:\n  menu/page_size: 5\n...\n").size() == 1);
    }
    for (const auto& name : {"../wanxiang.custom.yaml", "wanxiang.schema.yaml", "miancak_pinyin_private.custom.yaml", "lua.custom.yaml", "custom.yaml", "DEFAULT.custom.yaml"}) reject(name, canonical);
    for (const auto& value : {"0", "11", "-1", "1.5", "0x5", "01", "true", "inf", "99999999999999999999"}) reject("default.custom.yaml", "patch: {menu/page_size: " + std::string(value) + "}");
    for (const auto& text : std::vector<std::string>{
      "", "[]", "patch: {}", "patch: []", "patch: {menu/page_size: null}",
      "patch: {menu/page_size: 3}\npatch: {menu/page_size: 5}",
      "patch: {menu/page_size: 3, menu/page_size: 5}",
      "patch: {menu: {page_size: 3}, menu/page_size: 5}",
      "patch: {menu/page_size: 3}\n---\npatch: {menu/page_size: 5}",
      "patch: &p {menu/page_size: 3}", "patch: {menu/page_size: *p}",
      "patch: !!map {menu/page_size: 3}", "patch: {menu/page_size: !!int 3}",
      "patch: {menu/page_size: !custom 3}", "patch: {menu/page_size: [3]}",
      "patch: {schema/schema_id: other}", "patch: {schema/dependencies: [evil]}",
      "patch: {engine/processors: [lua_processor@evil]}",
      "patch: {translator/dictionary: ../../private}", "patch: {translator/user_dict: other}",
      "patch: {translator/enable_user_dict: true}", "patch: {grammar/language: other}",
      "patch: {__include: evil:/}", "patch: {speller/algebra/+: [evil]}",
      "patch: {switches/@0/reset: 1}", "patch: {switches/@7/reset: 4}",
      "patch: {translator/enable_completion: yes}", "patch: {translator/max_homophones: 33}",
      "patch: {translator/enable_sentence: false}", "patch: {translator/max_homographs: 4}",
      "patch: {translator/always_show_comments: true}", "patch: {translator/spelling_hints: 4}",
      "patch: {switches/@6/reset: 1}", "patch: {switches/@8/reset: 1}",
      "patch: {menu/page_size: 3}\nother: 1", "patch: {'menu//page_size': 3}",
      "patch: {'menu/page_size/': 3}", "patch: {menu: {}}", "patch: {menu/page_size: 3"
    }) reject("wanxiang.custom.yaml", text);
    reject("default.custom.yaml", "patch: {translator/max_homophones: 3}");
    std::string deep = "patch: "; for (int i=0;i<20;++i) deep+="{nested: "; deep+="1";for(int i=0;i<20;++i)deep+="}";reject("wanxiang.custom.yaml",deep);
    reject("wanxiang.custom.yaml", std::string(256*1024+1, '#'));
    std::string nodes = "patch: {"; for(int i=0;i<600;++i)nodes+="a: 1,";nodes+="}";reject("wanxiang.custom.yaml",nodes);
    bool legacy_refused = false;
    try { miancak::merge_custom("schema: {schema_id: miancak_pinyin}", "miancak_pinyin.custom.yaml", "patch: {switches/@7/reset: 1}"); }
    catch(const std::exception&) { legacy_refused=true; } require(legacy_refused);
    std::cout << "{\"scope\":\"actual pinned YAML parser and production custom-config validator\",\"checks\":" << checks << ",\"passed\":true}\n";
    return 0;
  } catch(const std::exception& error) {
    std::cerr << "CUSTOM_CONFIG_FAILED checks=" << checks << ": " << error.what() << "\n";return 1;
  }
}
