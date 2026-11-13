"""Check fullscreen focus and two-stream controls on an explicitly selected QA emulator.

Requires the debug APK installed and fixture_server.py running. Never targets a TV.
"""
import argparse
import json
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial', help='QA emulator serial, for example emulator-5562')
parser.add_argument('--port', type=int, default=18798)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Use a dedicated QA emulator. This check replaces its app session.')


def adb(*command, data=None):
    return subprocess.check_output(['adb', '-s', args.serial, *command], input=data, timeout=25)


def request(path, data=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(f'http://127.0.0.1:{args.port}{path}', data=body,
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def key(code):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(.3)


def nodes():
    for _ in range(3):
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/live-tv-check.xml')
        if b'UI hierchary dumped to' in result:
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/live-tv-check.xml')).iter('node'))
        time.sleep(1)
    raise AssertionError('Could not capture a fresh UI hierarchy')


def label(node):
    return node.get('text') or next((child.get('text') for child in node.iter('node')
                                   if child.get('text')), '')


def focused(text):
    tree = nodes()
    actual = [label(node) for node in tree if node.get('focused') == 'true']
    assert actual == [text], (text, actual)


def streams(expected):
    for _ in range(30):
        state = request('/qa/status')
        if state['open'] == expected:
            return
        time.sleep(.5)
    raise AssertionError(state)


request('/qa/guide_failure?enabled=1', {})
session = request('/Users/AuthenticateByName', {'Username': 'remote-check', 'Pw': 'fixture'})
seed = json.dumps({'base': f'http://10.0.2.2:{args.port}', 'token': session['AccessToken'],
                   'userId': session['User']['Id']}).encode()
adb('shell', 'am', 'force-stop', 'com.parmesh.livetv')
adb('shell', 'run-as', 'com.parmesh.livetv', 'mkdir', '-p', 'files')
adb('shell', 'run-as', 'com.parmesh.livetv', 'sh', '-c', "'cat > files/qa-session.json'", data=seed)
adb('shell', 'am', 'instrument', '-w', 'com.parmesh.livetv/.SeedSession')
adb('shell', 'am', 'start', '-n', 'com.parmesh.livetv/.MainActivity')
for _ in range(10):
    if any(node.get('text') == '1203 channels' for node in nodes()):
        break
else:
    raise AssertionError('Fixture lineup did not load')

key(84)
adb('shell', 'input', 'text', 'zzzznomatch')
key(66)
focused('Clear search')
assert not any(node.get('text') in ('−50', '+50') for node in nodes()), 'Empty list still has paging controls'
key(23)
focused('1  Amber One')
key(19)
focused('Search')
key(22)
key(23)
focused('All channels')
key(23)
focused('1  Amber One')
print('PASS: search and favorites empty-state recovery', flush=True)
key(82)
key(20)
key(23)
focused('Later')
key(19)
focused('Try again')
request('/qa/guide_failure?enabled=0', {})
key(23)
assert any(node.get('text') == 'Morning magazine' for node in nodes()), 'Guide retry did not restore listings'
key(4)
focused('1  Amber One')
print('PASS: guide failure, in-place retry, and focus return', flush=True)
key(92)
focused('1  Amber One')
key(23)
streams(1)
time.sleep(4)
key(20)
focused('2  Blue Two')
assert any(node.get('text') == 'Watching · 1  Amber One' for node in nodes()), 'Preview identifies the selected channel instead of the playing channel'
key(82)
focused('Play channel')
key(20)
focused('Play in inset')
key(23)
streams(2)
time.sleep(4)
key(19)
focused('1  Amber One')
key(22)
focused('Full screen')
key(23)
time.sleep(7)
assert not any(node.get('text') == 'Channels' for node in nodes()), 'Controls did not hide'
key(23)
focused('Channels')  # The reveal press must not also activate Channels.
# UIAutomator may wait longer than the auto-hide delay for a moving video to idle.
key(19)  # Reveal again if that happened before navigating to Swap.
key(22)
key(23)  # Swap without leaving fullscreen or opening another tuner.
streams(2)
key(22)
key(23)  # Close inset; focus must move to a surviving control.
focused('Channels')
streams(1)
time.sleep(7)
assert not any(node.get('text') == 'Channels' for node in nodes()), 'Controls did not hide again'
key(4)  # Back also works while all controls are hidden.
focused('1  Amber One')
key(4)
streams(0)
print('PASS: two streams, auto-hide, reveal without activation, swap, close inset, Back, tuner cleanup')
