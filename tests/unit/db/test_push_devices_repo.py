"""Unit tests for push_devices migration and repo."""

from __future__ import annotations

from pathlib import Path

import pytest

from octop.infra.db.migrate import run_migrations
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.push_devices import PushDeviceRepo
from octop.infra.db.repos.users import UserRepo


@pytest.fixture
def db(tmp_path: Path) -> SqlitePool:
    pool = SqlitePool(tmp_path / "octop.db")
    run_migrations(pool)
    return pool


def test_push_devices_table_exists(db: SqlitePool) -> None:
    with db.connect() as conn:
        names = {
            r["name"] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        v = conn.execute("SELECT version FROM _schema_version").fetchone()[0]
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(push_devices)").fetchall()}
    assert "push_devices" in names
    assert v == 26
    assert {"user_id", "platform", "device_token", "app_id", "last_seen"} <= cols


def test_upsert_dedupes_and_refreshes(db: SqlitePool) -> None:
    user_id = UserRepo(db).create(username="u", password_hash="h", role="user")
    repo = PushDeviceRepo(db)
    first = repo.upsert(
        user_id=user_id,
        platform="Android",
        device_token="token-abc-12345",
        app_id="chat.octop",
    )
    second = repo.upsert(
        user_id=user_id,
        platform="android",
        device_token="token-abc-12345",
        app_id="chat.octop.v2",
    )
    assert first.id == second.id
    assert second.app_id == "chat.octop.v2"
    assert second.last_seen >= first.last_seen
    assert len(repo.list_for_user(user_id)) == 1


def test_delete_device(db: SqlitePool) -> None:
    user_id = UserRepo(db).create(username="u", password_hash="h", role="user")
    repo = PushDeviceRepo(db)
    repo.upsert(user_id=user_id, platform="android", device_token="token-xyz-99999")
    assert repo.delete(user_id=user_id, platform="android", device_token="token-xyz-99999")
    assert repo.list_for_user(user_id) == []
