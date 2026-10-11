"""Generate the fixed Mandarin OGG assets used by VoiceClock."""

import argparse
import importlib.metadata
import json
import sys
from pathlib import Path

MODEL_REPO = "HojoAI/Hojo-TTS-Light-40M"
MODEL_REVISION = "c3cf21c77dc04b220aa4dabe20fc59e66a2a5485"
HOJO_SOURCE_COMMIT = "52ed7ef017363c6c49c71c3a8a649899142243f3"
VOICE = "hojo_zh_f_01"


def chinese_number(value: int) -> str:
    digits = "零一二三四五六七八九"
    if value < 10:
        return digits[value]
    return ("" if value < 20 else digits[value // 10]) + "十" + (digits[value % 10] if value % 10 else "")


def phrases(all_assets: bool) -> dict[str, str]:
    slots = range(48) if all_assets else (0,)
    result = {}
    for slot in slots:
        hour = slot // 2
        spoken_hour = hour % 12 or 12
        result[f"announcement_{hour:02d}{'30' if slot % 2 else '00'}"] = (
            f"现在是{'上午' if hour < 12 else '下午'}{chinese_number(spoken_hour)}点{'整' if slot % 2 == 0 else '半'}"
        )
    result["battery_low"] = "手机没电，请及时充电"
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="generate all 49 speech assets instead of two samples")
    parser.add_argument("--list", action="store_true", help="print the deterministic mapping without loading the model")
    parser.add_argument("--hojo-source", type=Path, default=Path(".voice-tools/Hojo-TTS-Light/Hojo-TTS-Light-40M"))
    parser.add_argument("--model-dir", type=Path, default=Path(".voice-tools/models/Hojo-TTS-Light-40M"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/res/raw"))
    args = parser.parse_args()
    mapping = phrases(args.all)
    if args.list:
        print(json.dumps(mapping, ensure_ascii=False, indent=2))
        return

    from huggingface_hub import snapshot_download
    import soundfile

    args.output.mkdir(parents=True, exist_ok=True)
    snapshot_download(MODEL_REPO, revision=MODEL_REVISION, local_dir=args.model_dir)
    sys.path.insert(0, str(args.hojo_source.resolve()))
    from infer import get_model

    tts = get_model(args.model_dir)
    for name, text in mapping.items():
        soundfile.write(
            args.output / f"{name}.ogg",
            tts.generate(text + "。", voice=VOICE),
            24_000,
            format="OGG",
            subtype="VORBIS",
        )

    if not args.all:
        return

    manifest = {
        "model": MODEL_REPO,
        "revision": MODEL_REVISION,
        "source_commit": HOJO_SOURCE_COMMIT,
        "voice": VOICE,
        "generation_prompt_suffix": "。",
        "chime": {
            "file": "chime.wav",
            "source": "user-provided ding_1.5s.wav",
            "sha256": "23218862A60B567D021F15828B08EC1D3FCCCE21D6BA8CDD351D9320E073875F",
            "duration_seconds": 1.5,
            "sample_rate": 44100,
            "channels": 1,
            "subtype": "PCM_16",
        },
        "versions": {
            name: importlib.metadata.version(name)
            for name in ("numpy", "onnx", "onnxruntime", "soundfile", "tokenizers", "huggingface_hub")
        },
        "assets": mapping,
    }
    Path("tools/voice_assets_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
