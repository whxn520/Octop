"""HTTP registration for mobile push devices."""

from __future__ import annotations

from typing import Any

import pytest

from tests.support.auth import bearer, bootstrap_admin


@pytest.fixture
async def env(patched_app_client: Any):
    c, srv, home = patched_app_client
    created = await bootstrap_admin(c, home)
    yield c, srv, created.json()["access_token"]


async def test_push_device_round_trip(env: Any) -> None:
    c, _srv, jwt = env
    headers = bearer(jwt)
    body = {
        "platform": "android",
        "device_token": "fcm-token-" + "x" * 20,
        "app_id": "chat.octop.debug",
    }
    r = await c.post("/api/push/devices", json=body, headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["platform"] == "android"
    assert data["device_token"] == body["device_token"]
    assert data["app_id"] == "chat.octop.debug"
    assert data["last_seen"] > 0

    r2 = await c.post("/api/push/devices", json=body, headers=headers)
    assert r2.status_code == 200
    assert r2.json()["device_token"] == body["device_token"]

    deleted = await c.request("DELETE", "/api/push/devices", json=body, headers=headers)
    assert deleted.status_code == 200, deleted.text
    assert deleted.json()["removed"] is True


async def test_push_device_requires_auth(env: Any) -> None:
    c, _srv, _jwt = env
    r = await c.post(
        "/api/push/devices",
        json={"platform": "android", "device_token": "fcm-token-" + "y" * 20},
    )
    assert r.status_code in {401, 403}
