"""FCM HTTP v1 sender using a Google service-account JSON.

Keeps the dependency surface small: cryptography + httpx (already used elsewhere).
When credentials are missing or invalid, callers treat send as a no-op.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_TOKEN_URL = "https://oauth2.googleapis.com/token"
_BODY_MAX = 180


@dataclass
class FcmCredentials:
    project_id: str
    client_email: str
    private_key: str


@dataclass
class FcmSendResult:
    ok: bool
    stale_token: bool = False
    detail: str = ""


def load_fcm_credentials(raw: str) -> FcmCredentials | None:
    """Parse inline JSON or a filesystem path to a service-account file."""
    text = (raw or "").strip()
    if not text:
        return None
    payload: dict[str, Any] | None = None
    if text.startswith("{"):
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            logger.warning("push.fcm_credentials_json is not valid JSON")
            return None
        if isinstance(parsed, dict):
            payload = parsed
    else:
        path = Path(text).expanduser()
        if not path.is_file():
            logger.warning("FCM credentials file not found: %s", path)
            return None
        try:
            parsed = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            logger.warning("failed to read FCM credentials from %s", path, exc_info=True)
            return None
        if isinstance(parsed, dict):
            payload = parsed
    if payload is None:
        return None
    project_id = str(payload.get("project_id") or "").strip()
    client_email = str(payload.get("client_email") or "").strip()
    private_key = str(payload.get("private_key") or "").replace("\\n", "\n").strip()
    if not project_id or not client_email or not private_key:
        logger.warning("FCM credentials missing project_id / client_email / private_key")
        return None
    return FcmCredentials(
        project_id=project_id,
        client_email=client_email,
        private_key=private_key,
    )


def truncate_body(text: str, *, limit: int = _BODY_MAX) -> str:
    cleaned = " ".join(text.split())
    if len(cleaned) <= limit:
        return cleaned
    return cleaned[: max(0, limit - 1)] + "…"


class FcmClient:
    """Cache an OAuth access token and POST message requests to FCM v1."""

    def __init__(self, credentials: FcmCredentials) -> None:
        self._credentials = credentials
        self._access_token: str | None = None
        self._token_expires_at: float = 0.0

    @property
    def project_id(self) -> str:
        return self._credentials.project_id

    def _mint_access_token(self) -> str:
        import httpx
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding

        now = int(time.time())
        header = {"alg": "RS256", "typ": "JWT"}
        claim = {
            "iss": self._credentials.client_email,
            "scope": _FCM_SCOPE,
            "aud": _TOKEN_URL,
            "iat": now,
            "exp": now + 3600,
        }
        import base64

        def b64url(data: bytes) -> str:
            return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

        signing_input = (
            f"{b64url(json.dumps(header, separators=(',', ':')).encode())}."
            f"{b64url(json.dumps(claim, separators=(',', ':')).encode())}"
        )
        from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

        key = serialization.load_pem_private_key(
            self._credentials.private_key.encode("utf-8"),
            password=None,
        )
        if not isinstance(key, RSAPrivateKey):
            raise RuntimeError("FCM private_key must be an RSA key")
        signature = key.sign(
            signing_input.encode("ascii"),
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
        assertion = f"{signing_input}.{b64url(signature)}"
        with httpx.Client(timeout=15.0) as client:
            resp = client.post(
                _TOKEN_URL,
                data={
                    "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion": assertion,
                },
            )
            resp.raise_for_status()
            body = resp.json()
        token = str(body.get("access_token") or "")
        expires_in = int(body.get("expires_in") or 3600)
        if not token:
            raise RuntimeError("FCM OAuth response missing access_token")
        self._access_token = token
        self._token_expires_at = time.time() + max(60, expires_in - 60)
        return token

    def _access_token_value(self) -> str:
        if self._access_token and time.time() < self._token_expires_at:
            return self._access_token
        return self._mint_access_token()

    def send(
        self,
        *,
        device_token: str,
        title: str,
        body: str,
        data: dict[str, str],
    ) -> FcmSendResult:
        import httpx

        url = f"https://fcm.googleapis.com/v1/projects/{self._credentials.project_id}/messages:send"
        payload = {
            "message": {
                "token": device_token,
                "notification": {"title": title, "body": truncate_body(body)},
                "data": {k: str(v) for k, v in data.items()},
                "android": {"priority": "HIGH"},
            }
        }
        try:
            token = self._access_token_value()
            with httpx.Client(timeout=15.0) as client:
                resp = client.post(
                    url,
                    headers={"Authorization": f"Bearer {token}"},
                    json=payload,
                )
        except Exception as exc:
            logger.warning("FCM send failed: %s", exc)
            return FcmSendResult(ok=False, detail=str(exc))

        if resp.status_code == 200:
            return FcmSendResult(ok=True)

        detail = resp.text[:300]
        stale = False
        try:
            err = resp.json()
            status = str(err.get("error", {}).get("status") or "")
            stale = status in {"NOT_FOUND", "INVALID_ARGUMENT", "UNREGISTERED"} or (
                "UNREGISTERED" in detail.upper()
            )
        except Exception:
            stale = "UNREGISTERED" in detail.upper()
        logger.warning("FCM send HTTP %s: %s", resp.status_code, detail)
        return FcmSendResult(ok=False, stale_token=stale, detail=detail)
