#ifndef MIANCAK_LEARNING_RECOVERY_H
#define MIANCAK_LEARNING_RECOVERY_H
#include <rime/dict/db.h>
#include <rime/dict/user_db.h>
#include <algorithm>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <limits>
#include <set>
#include <sstream>

namespace miancak {
struct LearningCounts { long active = 0, deleted = 0, unsupported = 0, records = 0; };
inline bool learned_text(const std::string& text) {
  size_t count = 0;
  for (size_t i = 0; i < text.size();) {
    unsigned char first = text[i++]; uint32_t cp = first; int extra = 0; uint32_t minimum = 0;
    if (first >= 0xc2 && first <= 0xdf) { cp &= 31; extra = 1; minimum = 0x80; }
    else if (first >= 0xe0 && first <= 0xef) { cp &= 15; extra = 2; minimum = 0x800; }
    else if (first >= 0xf0 && first <= 0xf4) { cp &= 7; extra = 3; minimum = 0x10000; }
    else if (first >= 0x80) return false;
    for (int n = 0; n < extra; ++n) {
      if (i == text.size() || (static_cast<unsigned char>(text[i]) & 0xc0) != 0x80) return false;
      cp = (cp << 6) | (static_cast<unsigned char>(text[i++]) & 63);
    }
    if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) || cp < 32 || (cp >= 0x7f && cp <= 0x9f) || ++count > 256) return false;
  }
  return count > 0;
}
inline bool learned_code(const std::string& code) {
  if (code.empty() || code.size() > 2048) return false;
  int syllable = 0;
  for (char c : code) {
    if (c == ' ') { if (!syllable) return false; syllable = 0; }
    else if (c < 'a' || c > 'z' || ++syllable > 8) return false;
  }
  return syllable > 0;
}
inline bool learned_count(const std::string& packed, int& commits) {
  std::istringstream input(packed); std::string token; std::set<char> seen;
  try {
    while (input >> token) {
      if (token.size() < 3 || token[1] != '=' || !seen.insert(token[0]).second) return false;
      auto value = token.substr(2); size_t end = 0;
      if (token[0] == 'c') {
        long long n = std::stoll(value, &end);
        if (end != value.size() || n < std::numeric_limits<int>::min() || n > std::numeric_limits<int>::max()) return false;
        commits = static_cast<int>(n);
      } else if (token[0] == 'd') {
        double n = std::stod(value, &end);
        if (end != value.size() || !std::isfinite(n) || n < 0) return false;
      } else if (token[0] == 't') {
        if (value.empty() || value.find_first_not_of("0123456789") != std::string::npos) return false;
        std::stoull(value, &end); if (end != value.size()) return false;
      } else return false;
    }
  } catch (const std::exception&) { return false; }
  return seen == std::set<char>({'c', 'd', 't'});
}
// Caller opens only an independent working copy, retaining a separate pristine
// snapshot: Rime's LevelDB read-only path can rewrite the opened copy.
inline LearningCounts recover_copied_legacy(const std::string& output, const std::string& name, long budget_ms) {
  if (name.empty() || name.size() > 64 || name.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789_") != std::string::npos || budget_ms < 1 || budget_ms > 60000)
    throw std::runtime_error("Invalid recovery request");
  if (std::ifstream(output).good()) throw std::runtime_error("Recovery output must be fresh");
  auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(budget_ms);
  auto* component = rime::UserDb::Require("userdb");
  if (!component) throw std::runtime_error("Recovery database component unavailable");
  std::unique_ptr<rime::Db> db(component->Create("pinyin_simp"));
  if (!db->OpenReadOnly() || !rime::UserDbHelper(db.get()).IsUserDb()) throw std::runtime_error("Copied source is not a recognized Rime user database");
  std::string temporary = output + ".partial"; LearningCounts counts;
  try {
    std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
    if (!out) throw std::runtime_error("Cannot stage recovered phrases");
    out << "# Recovered active Pinyin phrases from a verified closed copy.\n# Weights=max(1, commits); dynamic scores/context/deletions are not migrated.\n---\nname: " << name << "\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n";
    auto rows = db->QueryAll(); std::string key, packed;
    if (!rows) throw std::runtime_error("Cannot enumerate copied learning data");
    while (rows->GetNextRecord(&key, &packed)) {
      if (++counts.records > 200000 || std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Recovery record/time budget exceeded");
      int commits = 0;
      if (!learned_count(packed, commits)) { ++counts.unsupported; continue; }
      if (commits < 0) { ++counts.deleted; continue; }
      auto tab = key.find('\t');
      if (tab == std::string::npos || key.find('\t', tab + 1) != std::string::npos) { ++counts.unsupported; continue; }
      std::string code = key.substr(0, tab), text = key.substr(tab + 1);
      if (!code.empty() && code.back() == ' ') code.pop_back();
      if (!learned_code(code) || !learned_text(text)) { ++counts.unsupported; continue; }
      out << text << '\t' << code << '\t' << std::max(1, commits) << '\n'; ++counts.active;
      if (!out || out.tellp() > 64 * 1024 * 1024) throw std::runtime_error("Recovered dictionary exceeds output/storage bound");
    }
    rows.reset(); db->Close(); out.close();
    if (!out) throw std::runtime_error("Cannot finish recovered dictionary");
    if (counts.unsupported || !counts.active) { std::remove(temporary.c_str()); return counts; }
    if (std::rename(temporary.c_str(), output.c_str()) != 0) throw std::runtime_error("Cannot commit recovered dictionary");
    return counts;
  } catch (...) { std::remove(temporary.c_str()); throw; }
}
}
#endif
