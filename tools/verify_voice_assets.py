"""Verify VoiceClock's complete packaged audio set."""

import hashlib
import json
from pathlib import Path

import numpy as np
import soundfile

from generate_voice_assets import phrases


raw_dir = Path("app/src/main/res/raw")
manifest = json.loads(Path("tools/voice_assets_manifest.json").read_text(encoding="utf-8"))
expected_phrases = phrases(True)
expected_files = {f"{name}.ogg" for name in expected_phrases} | {"chime.wav"}
actual_files = {path.name for path in raw_dir.iterdir() if path.suffix in {".ogg", ".wav"}}

assert manifest["assets"] == expected_phrases
assert actual_files == expected_files, (sorted(expected_files - actual_files), sorted(actual_files - expected_files))
assert hashlib.sha256((raw_dir / "chime.wav").read_bytes()).hexdigest().upper() == manifest["chime"]["sha256"]

for path in sorted(raw_dir.iterdir()):
    audio, sample_rate = soundfile.read(path)
    assert audio.ndim == 1 and len(audio) > 0 and np.isfinite(audio).all() and np.max(np.abs(audio)) > 0.01, path
    assert sample_rate == (44_100 if path.name == "chime.wav" else 24_000), path

print("PASS: 49 speech assets + 1 user-provided chime")
