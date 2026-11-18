"""Mobile push device registrations (FCM / future APNs)."""

from __future__ import annotations

from dataclasses import dataclass

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos._base import DbRow, insert_returning_id, map_rows, now_ts


@dataclass(frozen=True)
class PushDeviceRow:
    id: int
    user_id: int
    platform: str
    device_token: str
    app_id: str
    created_at: int
    last_seen: int

    @classmethod
    def from_row(cls, row: DbRow) -> PushDeviceRow:
        return cls(
            id=int(row["id"]),
            user_id=int(row["user_id"]),
            platform=str(row["platform"]),
            device_token=str(row["device_token"]),
            app_id=str(row["app_id"] or ""),
            created_at=int(row["created_at"]),
            last_seen=int(row["last_seen"]),
        )


class PushDeviceRepo:
    def __init__(self, db: DatabasePool) -> None:
        self._db = db

    def upsert(
        self,
        *,
        user_id: int,
        platform: str,
        device_token: str,
        app_id: str = "",
    ) -> PushDeviceRow:
        """Insert or refresh ``last_seen`` for the same user/platform/token."""
        ts = now_ts()
        platform_norm = platform.strip().lower()
        token = device_token.strip()
        app = (app_id or "").strip()
        with self._db.transaction() as conn:
            existing = conn.execute(
                "SELECT * FROM push_devices "
                "WHERE user_id = ? AND platform = ? AND device_token = ?",
                (user_id, platform_norm, token),
            ).fetchone()
            if existing is not None:
                conn.execute(
                    "UPDATE push_devices SET last_seen = ?, app_id = ? WHERE id = ?",
                    (ts, app, int(existing["id"])),
                )
                row_id = int(existing["id"])
            else:
                row_id = insert_returning_id(
                    conn,
                    "INSERT INTO push_devices("
                    "user_id, platform, device_token, app_id, created_at, last_seen"
                    ") VALUES (?, ?, ?, ?, ?, ?)",
                    (user_id, platform_norm, token, app, ts, ts),
                )
        row = self.get(row_id)
        assert row is not None
        return row

    def get(self, device_id: int) -> PushDeviceRow | None:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT * FROM push_devices WHERE id = ?",
                (device_id,),
            ).fetchone()
        return PushDeviceRow.from_row(row) if row else None

    def list_for_user(self, user_id: int) -> list[PushDeviceRow]:
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM push_devices WHERE user_id = ? ORDER BY last_seen DESC",
                (user_id,),
            ).fetchall()
        return map_rows(rows, PushDeviceRow)

    def delete(
        self,
        *,
        user_id: int,
        platform: str,
        device_token: str,
    ) -> bool:
        platform_norm = platform.strip().lower()
        token = device_token.strip()
        with self._db.transaction() as conn:
            cur = conn.execute(
                "DELETE FROM push_devices WHERE user_id = ? AND platform = ? AND device_token = ?",
                (user_id, platform_norm, token),
            )
            return int(cur.rowcount or 0) > 0

    def delete_by_token(self, *, platform: str, device_token: str) -> int:
        """Drop stale tokens after FCM reports them invalid (any user)."""
        platform_norm = platform.strip().lower()
        token = device_token.strip()
        with self._db.transaction() as conn:
            cur = conn.execute(
                "DELETE FROM push_devices WHERE platform = ? AND device_token = ?",
                (platform_norm, token),
            )
            return int(cur.rowcount or 0)
