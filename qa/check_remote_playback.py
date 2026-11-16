"""Check fullscreen focus and two-stream controls on an explicitly selected QA emulator.

Requires the debug APK installed and fixture_server.py running. Never targets a TV.
"""
import argparse
import json
import subprocess
import struct
import re
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
        body = response.read()
        return json.loads(body) if body else None


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
    return node.get('text') or node.get('content-desc') or next((child.get('text') for child in node.iter('node')
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



def pictures_visible():
    # Assert pixels, not just the player's "Live" status: detached native surfaces
    # can keep decoding while rendering an entirely black picture.
    raw = adb('exec-out', 'screencap')
    width, height, pixel_format = struct.unpack_from('<III', raw)
    assert pixel_format == 1, ('Unexpected screenshot pixel format', pixel_format)
    pixels = memoryview(raw)[len(raw) - width * height * 4:]
    tree = nodes()
    inset = next(n for n in tree if n.get('content-desc') == 'Muted inset')
    left, top, right, bottom = map(int, re.findall(r'\d+', inset.get('bounds')))
    assert abs((right - left) * (bottom - top) / (width * height) - .125) < .005, 'Inset is not one-eighth of playback area'
    assert 0 < width - right < width * .03, 'Inset is not near the right edge'
    if not any(n.get('text') == 'Channels' for n in tree):
        assert 0 < height - bottom < height * .03, 'Inset did not return to the bottom corner'
    region = ((left + 8) / width, (top + 8) / height, (right - 8) / width, (bottom - 8) / height)
    for name, region in (('main', (.1, .1, .45, .4)), ('inset', region)):
        left, top, right, bottom = region
        colorful = 0
        for y in range(int(height * top), int(height * bottom), 8):
            for x in range(int(width * left), int(width * right), 8):
                rgb = pixels[(y * width + x) * 4:(y * width + x) * 4 + 3]
                colorful += max(rgb) > 180 and max(rgb) - min(rgb) > 120
        assert colorful > 30, (name, 'picture is black or missing', colorful)


def controls_hidden():
    for _ in range(6):
        time.sleep(6)
        if not any(node.get('text') == 'Channels' for node in nodes()):
            return
    raise AssertionError('Fullscreen controls did not hide after playback settled')


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
key(4)
focused('zzzznomatch')
key(20)
focused('Search')
key(23)
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
adb('shell', 'input', 'keyevent', '--longpress', '23')
focused('Play channel')
key(20)
focused('Guide')
key(23)
focused('Later')
key(19)
focused('Try again')
request('/qa/guide_failure?enabled=0', {})
key(23)
assert any(node.get('text') == 'Morning magazine' for node in nodes()), 'Guide retry did not restore listings'
key(23)  # Later.
key(22)
focused('Now')
key(23)
focused('Later')
assert any(node.get('text') == 'Morning magazine' for node in nodes()), 'Now did not restore current listings'
key(4)
focused('1  Amber One')
print('PASS: guide failure, in-place retry, and focus return', flush=True)
key(92)
focused('1  Amber One')
# Slow but progressing media must not be torn down by a buffering deadline.
request('/qa/slow_start?enabled=1', {})
slow_baseline = request('/qa/status')['opened']
key(23)
time.sleep(45)
assert request('/qa/status')['opened'] == slow_baseline + 1, 'Slow startup repeatedly reopened the channel'
assert not any('Buffering' in n.get('text', '') or 'Opening channel' in n.get('text', '')
               for n in nodes()), 'Slow startup never reached playback'
key(4)
streams(0)
request('/qa/slow_start?enabled=0', {})
print('PASS: slow startup finishes on the original session with provider-required headers', flush=True)
request('/qa/open_delay?ms=3000', {})
key(23)
assert request('/qa/status')['open'] == 0, 'Fixture did not delay the tune response'
key(127)  # Pause while PlaybackInfo is still pending, before a player exists.
streams(1)
request('/qa/open_delay?ms=0', {})
time.sleep(4)
assert any('Paused' in node.get('text', '') for node in nodes()), 'Pause during a pending tune was ignored'
key(22)
key(23)
focused('Channels')
assert any(node.get('text') == 'Resume live' for node in nodes()), 'Entering fullscreen hid the paused-stream controls'
key(4)
focused('1  Amber One')
key(85)
key(20)
focused('2  Blue Two')
assert any(node.get('text') == 'Watching · 1  Amber One' for node in nodes()), 'Preview identifies the selected channel instead of the playing channel'
request('/qa/open_failure?status=503', {})
before_requests = request('/qa/status')['open_requests']
key(82)
focused('Play channel')
key(20)
focused('Play in inset')
key(23)
for _ in range(10):
    if any('Could not open this channel' in node.get('text', '') for node in nodes()):
        break
else:
    raise AssertionError('Fixture did not exercise the inset tune failure')
streams(1)
assert request('/qa/status')['open_requests'] == before_requests + 1, 'Server error triggered unnecessary compatible-stream negotiation'
request('/qa/open_failure?status=0', {})
key(82)
focused('Play channel')
key(20)
focused('Retry inset')
key(23)
streams(2)
time.sleep(4)
key(19)
focused('1  Amber One')
key(22)
focused('Full screen')
key(23)
controls_hidden()
pictures_visible()
def both_live():
    key(19)
    tree = nodes()
    return (any(node.get('text') == 'Live' for node in tree)
            and not any('Buffering' in node.get('text', '') or 'Tuning' in node.get('text', '')
                        for node in tree))


before_opened = request('/qa/status')['opened']
request('/qa/advance_live_window', {})
time.sleep(20)
assert both_live(), 'Playback did not recover from an expired live window'
assert request('/qa/status')['opened'] == before_opened, 'Live-window recovery opened a new server stream'
print('PASS: stale live window recovers without a new server stream', flush=True)

request('/qa/media_failure?status=503', {})
time.sleep(3)
request('/qa/media_failure?status=0', {})
for _ in range(12):
    if both_live():break
    time.sleep(1)
else:raise AssertionError('Playback did not resume after a brief outage')
assert request('/qa/status')['opened'] == before_opened, 'Brief outage reopened streams'
print('PASS: brief media outage resumes on existing streams', flush=True)

# Only the old lease fails: recovery must obtain a fresh URL while the inset plays.
for mode in ('503', '403', 'ended', 'empty'):
    baseline = request('/qa/status')
    request('/qa/interrupt?channel=a&mode=' + mode, {})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        state = request('/qa/status')
        if state['opened'] > baseline['opened'] and both_live():break
        time.sleep(2)
    else:raise AssertionError(('No fresh-session recovery', mode, state))
    assert state['opened'] == baseline['opened'] + 1, ('Other stream reopened', mode, state)
    assert state['closed'] == baseline['closed'] + 1, ('Old lease leaked', mode, state)
    assert state['converted_requests'] == baseline['converted_requests'], 'Network recovery requested transcoding'
    assert state['open'] == 2 and state['peak_open'] == 2, 'Recovery exceeded two tuner leases'
    print('PASS: fresh-session recovery for ' + mode + ', inset preserved, no transcoding or tuner leak', flush=True)

# A server outage during renegotiation must keep waiting, then recover.
baseline = request('/qa/status')
request('/qa/open_failure?status=503', {})
request('/qa/interrupt?channel=a&mode=403', {})
deadline = time.monotonic() + 90
while request('/qa/status')['open_requests'] == baseline['open_requests']:
    assert time.monotonic() < deadline, 'No attempt to refresh interrupted stream'
    time.sleep(2)
request('/qa/open_failure?status=0', {})
deadline = time.monotonic() + 60
while time.monotonic() < deadline:
    if request('/qa/status')['open'] == 2 and both_live():break
    time.sleep(2)
else:raise AssertionError('Recovery stopped after a failed PlaybackInfo request')
assert request('/qa/status')['converted_requests'] == baseline['converted_requests']
print('PASS: server outage during recovery retries without transcoding', flush=True)
controls_hidden()

key(23)
focused('Channels')  # The reveal press must not also activate Channels.
key(85)  # Physical Play/Pause must pause the main picture, preserving the inset.
time.sleep(6)
assert any(node.get('text') == 'Resume live' for node in nodes()), 'Paused playback lost its resume control'
streams(2)
key(3)
streams(0)
adb('shell', 'am', 'start', '-n', 'com.parmesh.livetv/.MainActivity')
streams(2)
assert any(node.get('text') == 'Resume live' for node in nodes()), 'Returning to the app resumed paused playback'
key(22)
focused('Resume live')
key(23)
key(22)
key(23)  # Swap without leaving fullscreen or opening another tuner.
streams(2)
controls_hidden()
pictures_visible()
key(19)
focused('Channels')
key(22)
key(22)
key(22)
focused('Close inset')
key(23)  # Close inset; focus must move to a surviving control.
focused('Channels')
streams(1)
controls_hidden()
key(4)  # Back also works while all controls are hidden.
focused('1  Amber One')
key(4)
streams(0)
print('PASS: two streams, pause/resume, auto-hide, reveal without activation, swap, close inset, Back, tuner cleanup')

key(20)
key(20)
before_requests = request('/qa/status')['open_requests']
key(23)  # The fixture has no media source for channel 3.
for _ in range(10):
    if any('Could not open this channel' in node.get('text', '') for node in nodes()):
        break
else:
    raise AssertionError('Fixture did not exercise failed tuning')
assert request('/qa/status')['open_requests'] == before_requests + 2, 'Missing media source did not try the compatible profile'
key(22)
key(23)
focused('Channels')
assert any(node.get('text') == 'Try again' for node in nodes()), 'Entering fullscreen hid failed-stream recovery'
key(4)
key(4)
streams(0)
print('PASS: entering fullscreen preserves paused and failed-stream controls')

# Cancel recovery while paused or stopped; delayed work must never resurrect playback.
key(92)
key(23)
streams(1)
time.sleep(4)
request('/qa/interrupt?channel=a&mode=503', {})
key(127)
baseline = request('/qa/status')['open_requests']
time.sleep(35)
assert request('/qa/status')['open_requests'] == baseline, 'Paused stream reconnected'
key(126)
deadline = time.monotonic() + 75
while request('/qa/status')['open_requests'] == baseline:
    assert time.monotonic() < deadline, 'Resuming a stalled stream did not reconnect'
    time.sleep(2)
streams(1)
request('/qa/interrupt?channel=a&mode=503', {})
time.sleep(10)
key(4)
streams(0)
baseline = request('/qa/status')['open_requests']
time.sleep(35)
assert request('/qa/status')['open_requests'] == baseline, 'Stopped stream resurrected from pending recovery'
print('PASS: pause cancels recovery, resume reconnects, stop cancels pending recovery', flush=True)
