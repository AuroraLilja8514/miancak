#!/usr/bin/env python3
"""Literal phonetic pages; no implicit engine or punctuation conversion."""
from pathlib import Path
from copy import deepcopy
import xml.etree.ElementTree as ET

VOWELS = {"a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ"}
FINALS = "a o e i u ü ai ei ao ou an en ang eng ong er ia ie iao iu ian in iang ing iong ua uo uai ui uan un uang ueng üe üan ün".split()

def tone(final, number):
    if number == 0:
        return final
    if number not in range(1, 5):
        raise ValueError("Tone must be 0 through 4")
    lower = final.lower()
    if "a" in lower:
        index = lower.index("a")
    elif "e" in lower:
        index = lower.index("e")
    elif "ou" in lower:
        index = lower.index("o")
    else:
        index = max(i for i, char in enumerate(lower) if char in VOWELS)
    marked = VOWELS[lower[index]][number - 1]
    if final[index].isupper():
        marked = marked.upper()
    return final[:index] + marked + final[index + 1:]

def literal(text):
    return ":literal:'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

def bottom():
    row = ET.parse("res/xml/bottom_row.xml").getroot()
    row[2].set("key1", "switch_pinyin_finals")
    punctuation = row[3]
    punctuation.attrib.clear()
    punctuation.set("width", "1.1")
    for name, value in {"c": "。", "nw": "，", "ne": "？", "sw": "！", "se": "；", "n": "：", "s": "、", "w": "'", "e": "˙"}.items():
        punctuation.set(name, literal(value))
    return row

def save(root, name):
    ET.indent(root)
    ET.ElementTree(root).write(Path("res/xml") / (name + ".xml"), encoding="utf-8", xml_declaration=True)

def gen():
    pinyin = ET.parse("srcs/layouts/miancak_qwerty.xml").getroot()
    pinyin.set("name", "Pinyin text"); pinyin.set("script", "phonetic"); pinyin.set("bottom_row", "false")
    modmap = pinyin.find("modmap")
    if modmap is None:
        modmap = ET.SubElement(pinyin, "modmap")
    variants = set()
    for key in pinyin.iter("key"):
        center = key.get("c", "")
        if len(center) == 1 and "a" <= center <= "z":
            vowel = "ü" if center == "v" else center
            key.set("c", literal(vowel)); variants.add(vowel)
            if vowel in VOWELS:
                for number, direction in enumerate(["nw", "ne", "sw", "se"], 1):
                    value = tone(vowel, number); key.set(direction, literal(value)); variants.add(value)
        if key.get("nw") == "pinyin_separator":
            key.set("nw", literal("'"))
    for value in sorted(variants):
        ET.SubElement(modmap, "shift", {"a": literal(value), "b": literal(value.upper())})
    pinyin.append(bottom()); save(pinyin, "miancak_pinyin")

    finals = ET.Element("keyboard", {"name": "Pinyin finals", "script": "phonetic", "bottom_row": "false"})
    final_modmap = ET.SubElement(finals, "modmap")
    for start in range(0, len(FINALS), 9):
        row = ET.SubElement(finals, "row")
        for final in FINALS[start:start + 9]:
            attributes = {"c": literal(final), "width": str(10 / 9)}
            for number, direction in enumerate(["nw", "ne", "sw", "se"], 1):
                attributes[direction] = literal(tone(final, number))
            ET.SubElement(row, "key", attributes)
            for number in range(5):
                value = tone(final, number)
                ET.SubElement(final_modmap, "shift", {"a": literal(value), "b": literal(value.upper())})
    row = bottom()
    row[0].set("key0", "shift"); row[0].set("key2", "language_switch")
    row[2].set("key1", "backspace"); row[2].set("key2", "delete")
    finals.append(row)
    save(finals, "miancak_pinyin_finals")

    zhuyin = ET.Element("keyboard", {"name": "Zhuyin text", "script": "phonetic", "bottom_row": "false"})
    groups = ["ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎ", "ㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ", "ㄧㄨㄩㄚㄛㄜㄝㄞ", "ㄟㄠㄡㄢㄣㄤㄥㄦ"]
    for index, group in enumerate(groups):
        row = ET.SubElement(zhuyin, "row")
        if index == 3:
            ET.SubElement(row, "key", {"c": "shift", "ne": "language_switch", "role": "action", "width": "1.5"})
        for symbol in group:
            attributes = {"c": literal(symbol), "width": str(11 / len(group) if index != 3 else 1)}
            if index >= 2:
                for direction, mark in zip(["nw", "ne", "sw", "se"], ["", "ˊ", "ˇ", "ˋ"]):
                    attributes[direction] = literal(symbol + mark)
            ET.SubElement(row, "key", attributes)
        if index == 3:
            ET.SubElement(row, "key", {"c": "backspace", "ne": "delete", "role": "action", "width": "1.5"})
    row = bottom()
    for key in row:
        key.set("width", str(float(key.get("width")) * 1.1))
    zhuyin.append(row); save(zhuyin, "miancak_zhuyin")

if __name__ == "__main__":
    gen()
