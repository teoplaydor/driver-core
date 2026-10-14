#!/usr/bin/env python3
"""The watch's sounds for a voice note (src/common/start.mp3, end.mp3): two soft bell notes, up a fifth when the
recording starts, down when it ends; and quiet.mp3 — the silence that keeps the app running in the background.
Bells: a fundamental with two overtones, a quick attack and a decay, so they sound round on a small speaker.
usage: python3 watch/vela/tools/chimes.py  (needs ffmpeg with libmp3lame)"""
import math
import os
import struct
import subprocess
import tempfile
import wave

RATE = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'src', 'common')


def bell(freq, start, length, amp, buf):
    """One soft bell note added into buf from start (seconds)."""
    n0 = int(start * RATE)
    for i in range(int(length * RATE)):
        t = i / RATE
        env = min(1.0, t / 0.006) * math.exp(-t / 0.11)
        v = (math.sin(2 * math.pi * freq * t) + 0.35 * math.sin(2 * math.pi * 2 * freq * t) * math.exp(-t / 0.05)
             + 0.12 * math.sin(2 * math.pi * 3 * freq * t) * math.exp(-t / 0.03))
        if n0 + i < len(buf):
            buf[n0 + i] += amp * env * v


def write(name, notes, secs):
    buf = [0.0] * int(secs * RATE)
    for f, at in notes:
        bell(f, at, secs - at, 0.32, buf)
    peak = max(abs(v) for v in buf) or 1
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, name + '.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b''.join(struct.pack('<h', int(v / peak * 0.8 * 32767)) for v in buf))
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', wav, '-c:a', 'libmp3lame', '-b:a', '64k',
                        os.path.join(OUT, name + '.mp3')], check=True)


C6, G6 = 1046.5, 1568.0
write('start', [(C6, 0.0), (G6, 0.09)], 0.42)  # up: speak now
write('end', [(G6, 0.0), (C6, 0.09)], 0.42)  # down: done
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=mono', '-t', '12',
                '-c:a', 'libmp3lame', '-b:a', '32k', os.path.join(OUT, 'quiet.mp3')], check=True)
