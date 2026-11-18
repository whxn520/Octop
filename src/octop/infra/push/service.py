"""Fan out dashboard_push payloads to registered mobile devices."""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from octop.infra.db.repos.push_devices import PushDeviceRepo, PushDeviceRow
from octop.infra.push.fcm import FcmClient, load_fcm_credentials

if TYPE_CHECKING:
    from octop.config import PushConfig

logger = logging.getLogger(__name__)


class MobilePushService:
    """Register devices and deliver system notifications (FCM first)."""

    def __init__(
        self,
        *,
        repo: PushDeviceRepo,
        config: PushConfig,
    ) -> None:
        self._repo = repo
        self._config = config
        self._fcm: FcmClient | None = None
        creds = load_fcm_credentials(config.fcm_credentials_json)
        if creds is not None:
            self._fcm = FcmClient(creds)

    @property
    def enabled(self) -> bool:
        return self._fcm is not None

    def replace_repo(self, repo: PushDeviceRepo) -> None:
        self._repo = repo

    def register(
        self,
        *,
        user_id: int,
        platform: str,
        device_token: str,
        app_id: str = "",
    ) -> PushDeviceRow:
        return self._repo.upsert(
            user_id=user_id,
            platform=platform,
            device_token=device_token,
            app_id=app_id,
        )

    def unregister(
        self,
        *,
        user_id: int,
        platform: str,
        device_token: str,
    ) -> bool:
        return self._repo.delete(
            user_id=user_id,
            platform=platform,
            device_token=device_token,
        )

    async def notify_user(
        self,
        *,
        user_id: int,
        agent_id: str,
        thread_id: str,
        agent_name: str,
        text: str,
    ) -> None:
        """Best-effort FCM fan-out; never raises into the chat path."""
        if self._fcm is None or not text.strip():
            return
        try:
            devices = await asyncio.to_thread(self._repo.list_for_user, user_id)
        except Exception:
            logger.warning("list push devices failed for user_id=%s", user_id, exc_info=True)
            return
        android = [d for d in devices if d.platform == "android"]
        if not android:
            return
        title = agent_name.strip() or agent_id
        data = {
            "type": "dashboard_push",
            "agent_id": agent_id,
            "thread_id": thread_id,
            "agent_name": title,
        }
        for device in android:
            try:
                result = await asyncio.to_thread(
                    self._fcm.send,
                    device_token=device.device_token,
                    title=title,
                    body=text,
                    data=data,
                )
            except Exception:
                logger.warning(
                    "FCM send raised for device_id=%s",
                    device.id,
                    exc_info=True,
                )
                continue
            if result.stale_token:
                try:
                    await asyncio.to_thread(
                        self._repo.delete_by_token,
                        platform=device.platform,
                        device_token=device.device_token,
                    )
                except Exception:
                    logger.warning(
                        "failed to drop stale push token device_id=%s",
                        device.id,
                        exc_info=True,
                    )
