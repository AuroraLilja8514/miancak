#ifndef MIANCAK_CUSTOM_CONFIG_H
#define MIANCAK_CUSTOM_CONFIG_H

#include <yaml-cpp/yaml.h>
#include <yaml-cpp/parser.h>
#include <yaml-cpp/eventhandler.h>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <stdexcept>

namespace miancak {
// These are configuration data, not an executable-schema import interface.
inline bool custom_filename(const std::string& name) {
  return name == "default.custom.yaml" || name == "wanxiang.custom.yaml" || name == "miancak_pinyin.custom.yaml";
}

struct CustomYamlLimits : YAML::EventHandler {
  int depth = 0, nodes = 0, documents = 0;
  void node(const std::string& tag = "?", YAML::anchor_t anchor = 0) {
    if (++nodes > 1024 || anchor || (tag != "?" && tag != "!"))
      throw std::runtime_error("Custom YAML: tags, anchors or excessive nodes are unsupported");
  }
  void container(const std::string& tag, YAML::anchor_t anchor) {
    node(tag, anchor);
    if (++depth > 12) throw std::runtime_error("Custom YAML nesting exceeds 12");
  }
  void OnDocumentStart(const YAML::Mark&) override {
    if (++documents > 1) throw std::runtime_error("Custom YAML requires one document");
  }
  void OnDocumentEnd() override {}
  void OnNull(const YAML::Mark&, YAML::anchor_t) override { throw std::runtime_error("Custom YAML null/deletion is unsupported"); }
  void OnAlias(const YAML::Mark&, YAML::anchor_t) override { throw std::runtime_error("Custom YAML aliases are unsupported"); }
  void OnAnchor(const YAML::Mark&, const std::string&) override { throw std::runtime_error("Custom YAML anchors are unsupported"); }
  void OnScalar(const YAML::Mark&, const std::string& tag, YAML::anchor_t anchor, const std::string& value) override {
    node(tag, anchor);
    if (value.size() > 256) throw std::runtime_error("Custom YAML scalar exceeds 256 bytes");
  }
  void OnSequenceStart(const YAML::Mark&, const std::string& tag, YAML::anchor_t anchor, YAML::EmitterStyle::value) override { container(tag, anchor); }
  void OnSequenceEnd() override { --depth; }
  void OnMapStart(const YAML::Mark&, const std::string& tag, YAML::anchor_t anchor, YAML::EmitterStyle::value) override { container(tag, anchor); }
  void OnMapEnd() override { --depth; }
};

using CustomValues = std::map<std::string, YAML::Node>;

inline void custom_value(const std::string& filename, const std::string& key, const YAML::Node& value) {
  if (!value.IsScalar()) throw std::runtime_error("Custom patch values must be scalar settings");
  std::string text = value.Scalar();
  int low = 0, high = 0;
  if (key == "menu/page_size") { low = 1; high = 10; }
  else if (filename == "default.custom.yaml") throw std::runtime_error("default.custom.yaml supports menu/page_size only");
  else if (key == "translator/enable_completion" || key == "translator/enable_word_completion") {
    if (text != "true" && text != "false") throw std::runtime_error("Custom boolean requires true or false");
    return;
  }
  else if (key == "translator/max_homophones") { low = 1; high = 32; }
  else if (key == "switches/@3/reset" || key == "switches/@4/reset" || key == "switches/@5/reset") { low = 0; high = 1; }
  else if (key == "switches/@7/reset") { low = 0; high = 3; }
  else throw std::runtime_error("Unsupported custom patch setting; see supported configuration help");
  if (text.empty() || text.size() > 2 || text.find_first_not_of("0123456789") != std::string::npos ||
      (text.size() > 1 && text[0] == '0')) throw std::runtime_error("Custom integer requires canonical decimal digits");
  int number = std::stoi(text);
  if (number < low || number > high) throw std::runtime_error("Custom numeric setting is outside its supported range");
}

inline void flatten_custom(const std::string& filename, const YAML::Node& node, const std::string& prefix, CustomValues& values) {
  if (!node.IsMap() || !node.size()) throw std::runtime_error("Custom patch requires a nonempty mapping");
  std::set<std::string> keys;
  for (const auto& item : node) {
    if (!item.first.IsScalar()) throw std::runtime_error("Custom patch keys must be scalar paths");
    std::string key = item.first.Scalar();
    if (key.empty() || key.size() > 80 || key.front() == '/' || key.back() == '/' || key.find("//") != std::string::npos ||
        key.find_first_not_of("abcdefghijklmnopqrstuvwxyz_0123456789/@") != std::string::npos || !keys.insert(key).second)
      throw std::runtime_error("Invalid or duplicate custom patch path");
    std::string path = prefix.empty() ? key : prefix + "/" + key;
    if (item.second.IsMap()) flatten_custom(filename, item.second, path, values);
    else {
      custom_value(filename, path, item.second);
      if (!values.emplace(path, YAML::Clone(item.second)).second || values.size() > 64)
        throw std::runtime_error("Duplicate or excessive custom patch settings");
    }
  }
}

inline CustomValues parse_custom(const std::string& filename, const std::string& text) {
  if (!custom_filename(filename)) throw std::runtime_error("Choose default.custom.yaml, wanxiang.custom.yaml or miancak_pinyin.custom.yaml");
  if (text.empty() || text.size() > 256 * 1024 || text.find('\0') != std::string::npos)
    throw std::runtime_error("Custom YAML must be nonempty UTF-8 text of at most 256 KiB");
  std::istringstream input(text);
  YAML::Parser parser(input); CustomYamlLimits limits;
  while (parser.HandleNextDocument(limits)) {}
  auto root = YAML::Load(text);
  if (!root.IsMap() || root.size() != 1 || !root["patch"])
    throw std::runtime_error("Custom YAML requires exactly one patch mapping");
  CustomValues values; flatten_custom(filename, root["patch"], "", values);
  return values;
}

inline std::string canonical_custom(const std::string& filename, const std::string& text) {
  YAML::Node result;
  for (const auto& item : parse_custom(filename, text)) result["patch"][item.first] = item.second;
  YAML::Emitter output; output << result;
  if (!output.good()) throw std::runtime_error("Cannot encode custom patch");
  return std::string(output.c_str()) + "\n";
}

inline std::string merge_custom(const std::string& schema, const std::string& filename, const std::string& text) {
  if (schema.size() > 1024 * 1024) throw std::runtime_error("Managed schema is too large");
  auto root = YAML::Load(schema);
  if (!root.IsMap()) throw std::runtime_error("Invalid managed schema");
  const YAML::Node view = root;
  auto include = view["__include"];
  bool base = include && include.IsScalar() && include.Scalar() == "wanxiang.schema:/";
  auto identity = view["schema"];
  bool private_schema = identity && identity["schema_id"] && identity["schema_id"].as<std::string>() == "miancak_pinyin_private";
  for (const auto& item : parse_custom(filename, text)) {
    if (item.first.find("switches/") == 0) {
      if (private_schema) continue;  // Base switch resets are normal-pipeline preferences.
      if (!base) throw std::runtime_error("Switch reset patches require bundled Base");
    }
    root["__patch"][item.first] = item.second;
  }
  YAML::Emitter output; output << root;
  if (!output.good()) throw std::runtime_error("Cannot encode managed configuration");
  return std::string(output.c_str()) + "\n";
}
}  // namespace miancak
#endif
