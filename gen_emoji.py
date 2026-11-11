
# A finite watchdog also bounds imports, child commands and shutdown.
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parents[0] / "scripts"))
    from run_with_timeout import guard_script as _guard_script
    _guard_script(__file__)

import urllib.request
import os.path

EMOJIS_PATH = 'res/raw/emojis.txt'
EMOJI_TEST_PATH = 'emoji-test.txt'
EMOJI_TEST_URL = 'https://unicode.org/Public/emoji/latest/emoji-test.txt'

def rawEmojiFromCodes(codes):
    return ''.join([chr(int(c, 16)) for c in codes])

def getEmojiTestContents():
    if os.path.exists(EMOJI_TEST_PATH):
        print(f'Using existing {EMOJI_TEST_PATH}')
    else:
        print(f'Downloading {EMOJI_TEST_URL}')
        from pathlib import Path
        import shutil
        temporary = Path(EMOJI_TEST_PATH + '.partial')
        with urllib.request.urlopen(EMOJI_TEST_URL, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        temporary.replace(EMOJI_TEST_PATH)
    return open(EMOJI_TEST_PATH, mode='r', encoding='UTF-8').read()
        

emoji_list = []
group_indices = []
for line in getEmojiTestContents().splitlines():
    if line.startswith('# group:'):
        if len(group_indices) == 0 or len(emoji_list) > group_indices[-1]:
            group_indices.append(len(emoji_list))
    elif not line.startswith('#') and 'fully-qualified' in line:
        codes = line.split(';')[0].split()
        emoji_list.append(rawEmojiFromCodes(codes))

with open(EMOJIS_PATH, 'w', encoding='UTF-8') as emojis:
    for e in emoji_list:
        emojis.write(f'{e}\n')
    emojis.write('\n')

    emojis.write(' '.join([str(g) for g in group_indices]))
    emojis.write('\n')

print(f'Parsed {len(emoji_list)} emojis in {len(group_indices)}')
