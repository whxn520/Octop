"""Mobile push device registration."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from octop.api.deps import current_user, get_server
from octop.infra.errors import ErrorCode, OctopError
from octop.infra.push.service import MobilePushService
from octop.infra.server import OctopServer
from octop.infra.users.identity import User

router = APIRouter()


class PushDeviceBody(BaseModel):
    platform: Literal["android"] = Field(description="Device platform (android first).")
    device_token: str = Field(
        min_length=8,
        max_length=4096,
        description="FCM registration token.",
    )
    app_id: str = Field(
        default="",
        max_length=128,
        description="Optional application id / package name.",
    )


class PushDeviceResponse(BaseModel):
    platform: str
    device_token: str
    app_id: str
    last_seen: int


def _push_service(server: OctopServer) -> MobilePushService:
    runtime = server.app_runtime
    if runtime is not None:
        mobile_push = runtime.gateway.mobile_push
        if mobile_push is not None:
            return mobile_push
    services = server.services
    if services is None:
        raise OctopError(ErrorCode.INTERNAL_ERROR, "push service unavailable", status=503)
    return MobilePushService(repo=services.push_device_repo, config=services.config.push)


@router.post(
    "/devices",
    response_model=PushDeviceResponse,
    summary="Register or refresh a mobile push device",
)
async def register_push_device(
    body: PushDeviceBody,
    user: User = Depends(current_user),
    server: OctopServer = Depends(get_server),
) -> PushDeviceResponse:
    push = _push_service(server)
    row = push.register(
        user_id=user.id,
        platform=body.platform,
        device_token=body.device_token,
        app_id=body.app_id,
    )
    return PushDeviceResponse(
        platform=row.platform,
        device_token=row.device_token,
        app_id=row.app_id,
        last_seen=row.last_seen,
    )


@router.delete(
    "/devices",
    summary="Unregister a mobile push device",
)
async def unregister_push_device(
    body: PushDeviceBody,
    user: User = Depends(current_user),
    server: OctopServer = Depends(get_server),
) -> dict[str, bool]:
    push = _push_service(server)
    removed = push.unregister(
        user_id=user.id,
        platform=body.platform,
        device_token=body.device_token,
    )
    return {"removed": removed}
