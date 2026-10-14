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
announcement_files = manifest["announcements"]["files"]
expected_announcements = {f"{name}.mp3" for name in expected_phrases if name.startswith("announcement_")}
assert set(announcement_files) == expected_announcements
expected_files = expected_announcements | {"battery_low.ogg", "chime.wav"}
actual_files = {path.name for path in raw_dir.iterdir() if path.is_file()}

assert manifest["assets"] == expected_phrases
assert actual_files == expected_files, (sorted(expected_files - actual_files), sorted(actual_files - expected_files))
assert hashlib.sha256((raw_dir / "chime.wav").read_bytes()).hexdigest().upper() == manifest["chime"]["sha256"]

for name, record in announcement_files.items():
    packaged = raw_dir / name
    assert hashlib.sha256(packaged.read_bytes()).hexdigest().upper() == record["sha256"], name
    source = Path(record["source"])
    assert packaged.read_bytes() == source.read_bytes(), name
    info = soundfile.info(packaged)
    assert info.format == "MP3" and info.channels == 1, name
    assert abs(info.duration - record["duration_seconds"]) < 0.000001, name
    assert manifest["chime"]["duration_seconds"] + info.duration / 0.82 < 15, name

for path in sorted(raw_dir.iterdir()):
    audio, sample_rate = soundfile.read(path)
    assert audio.ndim == 1 and len(audio) > 0 and np.isfinite(audio).all() and np.max(np.abs(audio)) > 0.01, path
    assert sample_rate == (44_100 if path.name == "chime.wav" else 24_000), path

print("PASS: 48 original speaker MP3 announcements + 1 battery reminder + 1 user-provided chime")
