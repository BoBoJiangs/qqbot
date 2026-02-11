"""Usage counter data repository.

Handles all data access operations for usage counters including
daily/monthly counting and caching.
"""
import json
import os
import threading
import time
from datetime import datetime, timezone
from typing import Dict, Any, Optional
from config import settings


class UsageRepository:
    """Repository for usage counter data persistence and caching."""

    def __init__(self, file_path: Optional[str] = None, cache_ttl: float = 1.0):
        """Initialize repository.

        Args:
            file_path: Path to usage counters JSON file. Defaults to settings.usage_counters_file
            cache_ttl: Cache time-to-live in seconds
        """
        self._file_path = file_path or settings.usage_counters_file
        self._cache_ttl = cache_ttl
        self._lock = threading.Lock()
        self._cache = {
            "loaded_at": 0.0,
            "mtime": None,
            "data": {"counters": {}}
        }

    def _read_json_file(self, path: str, default: Any) -> Any:
        """Read JSON file with error handling."""
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return default
        except Exception:
            return default

    def _atomic_write_json(self, path: str, data: Any):
        """Atomically write JSON file."""
        import secrets
        content = json.dumps(data, ensure_ascii=False, indent=2)
        directory = os.path.dirname(os.path.abspath(path)) or "."
        base = os.path.basename(path)
        tmp_path = os.path.join(directory, f".{base}.{secrets.token_hex(8)}.tmp")
        with open(tmp_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        os.replace(tmp_path, path)

    def load(self) -> Dict[str, Any]:
        """Load usage counters data with caching.

        Returns:
            Dict containing usage counters data with "counters" key
        """
        now = time.time()
        exists = os.path.exists(self._file_path)
        mtime = os.path.getmtime(self._file_path) if exists else None

        # Return cached data if fresh
        if (now - self._cache["loaded_at"]) < self._cache_ttl and mtime == self._cache["mtime"]:
            return self._cache["data"]

        with self._lock:
            # Double-check after acquiring lock
            exists = os.path.exists(self._file_path)
            mtime = os.path.getmtime(self._file_path) if exists else None

            data = self._read_json_file(self._file_path, {"counters": {}})
            if "counters" not in data or not isinstance(data["counters"], dict):
                data = {"counters": {}}

            if not exists:
                self._atomic_write_json(self._file_path, data)

            self._cache.update({
                "loaded_at": now,
                "mtime": mtime,
                "data": data
            })
            return data

    def save(self, data: Dict[str, Any]) -> None:
        """Save usage counters data and update cache.

        Args:
            data: Dict containing usage counters data with "counters" key
        """
        with self._lock:
            self._atomic_write_json(self._file_path, data)
            self._cache.update({
                "loaded_at": time.time(),
                "mtime": os.path.getmtime(self._file_path),
                "data": data
            })

    def get_counter(self, member_key: str, now: Optional[datetime] = None) -> Dict[str, Any]:
        """Get usage counter for a member.

        Args:
            member_key: Member key

        Returns:
            Counter data dict with daily_count, monthly_count, total_count, etc.
        """
        if now is None:
            now = datetime.now(timezone.utc)

        data = self.load()
        counters = data.get("counters", {})
        raw = counters.get(member_key, {
            "daily_date": None,
            "daily_count": 0,
            "monthly_ym": None,
            "monthly_count": 0,
            "total_count": 0,
            "error_count": 0,
        })

        day = now.date().isoformat()
        ym = f"{now.year:04d}-{now.month:02d}"

        daily_is_today = raw.get("daily_date") == day
        monthly_is_current = raw.get("monthly_ym") == ym

        daily_count = int(raw.get("daily_count") or 0) if daily_is_today else 0
        total_count = int(raw.get("total_count") or 0) if daily_is_today else 0
        error_count = int(raw.get("error_count") or 0) if daily_is_today else 0
        monthly_count = int(raw.get("monthly_count") or 0) if monthly_is_current else 0

        return {
            "daily_date": day,
            "daily_count": daily_count,
            "monthly_ym": ym,
            "monthly_count": monthly_count,
            "total_count": total_count,
            "error_count": error_count,
            "updated_at": raw.get("updated_at", ""),
        }

    def increment_usage(
        self,
        member_key: str,
        now: datetime,
        daily_limit: Optional[int] = None,
        monthly_limit: Optional[int] = None,
        is_error: bool = False
    ) -> Dict[str, Any]:
        """Increment usage counter for a member.

        Args:
            member_key: Member key
            now: Current datetime
            daily_limit: Daily limit to enforce (None for unlimited)
            monthly_limit: Monthly limit to enforce (None for unlimited)
            is_error: Whether this is an error report

        Returns:
            Updated counter data

        Raises:
            HTTPException: If limit exceeded
        """
        from fastapi import HTTPException

        usage = self.load()
        counters = usage.setdefault("counters", {})
        c = counters.setdefault(member_key, {})

        day = now.date().isoformat()
        ym = f"{now.year:04d}-{now.month:02d}"

        # Reset daily counter if date changed
        if c.get("daily_date") != day:
            c["daily_date"] = day
            c["daily_count"] = 0
            c["total_count"] = 0
            c["error_count"] = 0

        # Reset monthly counter if month changed
        if c.get("monthly_ym") != ym:
            c["monthly_ym"] = ym
            c["monthly_count"] = 0

        # Check daily limit
        if daily_limit is not None and int(c.get("daily_count") or 0) >= daily_limit:
            raise HTTPException(
                status_code=429,
                detail=f"普通用户每天仅能调用{daily_limit}次"
            )

        # Check monthly limit
        if monthly_limit is not None and int(c.get("monthly_count") or 0) >= monthly_limit:
            raise HTTPException(status_code=429, detail="月卡本月调用次数已用完")

        # Increment counters
        c["daily_count"] = int(c.get("daily_count") or 0) + 1
        c["monthly_count"] = int(c.get("monthly_count") or 0) + 1
        c["total_count"] = int(c.get("total_count") or 0) + 1
        if is_error:
            c["error_count"] = int(c.get("error_count") or 0) + 1

        c["updated_at"] = self._format_iso(now)
        self.save(usage)

        return c

    @staticmethod
    def _format_iso(dt: Optional[datetime]) -> str:
        """Format datetime as ISO string.

        Args:
            dt: Datetime object or None

        Returns:
            ISO formatted string or empty string
        """
        if not dt:
            return ""
        return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def clear_cache(self) -> None:
        """Clear the data cache."""
        with self._lock:
            self._cache["loaded_at"] = 0.0
            self._cache["mtime"] = None


# Global repository instance
_usage_repository: Optional[UsageRepository] = None


def get_usage_repository() -> UsageRepository:
    """Get or create global usage repository instance.

    Returns:
        UsageRepository instance
    """
    global _usage_repository
    if _usage_repository is None:
        _usage_repository = UsageRepository()
    return _usage_repository
