"""MobilePushService register / notify behaviour."""

from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import pytest

from octop.config import PushConfig
from octop.infra.db.migrate import run_migrations
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.push_devices import PushDeviceRepo
from octop.infra.db.repos.users import UserRepo
from octop.infra.push.fcm import FcmSendResult, load_fcm_credentials, truncate_body
from octop.infra.push.service import MobilePushService


@pytest.fixture
def repo(tmp_path: Path) -> tuple[PushDeviceRepo, int]:
    pool = SqlitePool(tmp_path / "octop.db")
    run_migrations(pool)
    user_id = UserRepo(pool).create(username="u", password_hash="h", role="user")
    return PushDeviceRepo(pool), user_id


def test_load_credentials_empty() -> None:
    assert load_fcm_credentials("") is None
    assert load_fcm_credentials("{") is None


def test_load_credentials_from_inline_json() -> None:
    raw = (
        '{"project_id":"p","client_email":"a@b.com",'
        '"private_key":"-----BEGIN PRIVATE KEY-----\\nABC\\n-----END PRIVATE KEY-----\\n"}'
    )
    creds = load_fcm_credentials(raw)
    assert creds is not None
    assert creds.project_id == "p"
    assert "BEGIN PRIVATE KEY" in creds.private_key


def test_truncate_body() -> None:
    assert truncate_body("hello") == "hello"
    long = "x" * 300
    out = truncate_body(long, limit=20)
    assert len(out) == 20
    assert out.endswith("…")


def test_service_disabled_without_credentials(repo: tuple[PushDeviceRepo, int]) -> None:
    devices, user_id = repo
    svc = MobilePushService(repo=devices, config=PushConfig())
    assert not svc.enabled
    row = svc.register(
        user_id=user_id,
        platform="android",
        device_token="token-abcdefg",
        app_id="app",
    )
    assert row.device_token == "token-abcdefg"


@pytest.mark.asyncio
async def test_notify_skips_when_unconfigured(repo: tuple[PushDeviceRepo, int]) -> None:
    devices, user_id = repo
    svc = MobilePushService(repo=devices, config=PushConfig())
    devices.upsert(user_id=user_id, platform="android", device_token="token-abcdefg")
    await svc.notify_user(
        user_id=user_id,
        agent_id="agent-1",
        thread_id="thread-1",
        agent_name="Bot",
        text="hello",
    )


@pytest.mark.asyncio
async def test_notify_sends_and_drops_stale(repo: tuple[PushDeviceRepo, int]) -> None:
    devices, user_id = repo
    svc = MobilePushService(repo=devices, config=PushConfig())
    devices.upsert(user_id=user_id, platform="android", device_token="stale-token-1")
    fake = MagicMock()
    fake.send.return_value = FcmSendResult(ok=False, stale_token=True, detail="UNREGISTERED")
    svc._fcm = fake  # noqa: SLF001
    await svc.notify_user(
        user_id=user_id,
        agent_id="agent-1",
        thread_id="thread-1",
        agent_name="Bot",
        text="ping",
    )
    fake.send.assert_called_once()
    kwargs: dict[str, Any] = fake.send.call_args.kwargs
    assert kwargs["device_token"] == "stale-token-1"
    assert kwargs["title"] == "Bot"
    assert devices.list_for_user(user_id) == []
