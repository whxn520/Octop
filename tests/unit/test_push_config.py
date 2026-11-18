"""PushConfig parsing from config.json / env."""

from __future__ import annotations

import json
from pathlib import Path

from octop.config import OctopConfig, load_config


def test_default_push_empty() -> None:
    assert OctopConfig().push.fcm_credentials_json == ""


def test_load_push_section(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.delenv("OCTOP_PUSH_FCM_CREDENTIALS_JSON", raising=False)
    monkeypatch.delenv("OCTOP_PUSH_FCM_CREDENTIALS_PATH", raising=False)
    path = tmp_path / "config.json"
    path.write_text(
        json.dumps({"push": {"fcm_credentials_json": "/tmp/sa.json"}}),
        encoding="utf-8",
    )
    cfg = load_config(path)
    assert cfg.push.fcm_credentials_json == "/tmp/sa.json"


def test_env_overrides_push_path(tmp_path: Path, monkeypatch) -> None:
    path = tmp_path / "config.json"
    path.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("OCTOP_PUSH_FCM_CREDENTIALS_PATH", "C:/secrets/fcm.json")
    cfg = load_config(path)
    assert cfg.push.fcm_credentials_json == "C:/secrets/fcm.json"
