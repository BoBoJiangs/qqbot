"""Member data repository.

Handles all data access operations for members including CRUD,
batch operations, and caching.
"""
import json
import os
import threading
import time
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Optional, Any
from config import settings


class MemberRepository:
    """Repository for member data persistence and caching."""

    def __init__(self, file_path: Optional[str] = None, cache_ttl: float = 2.0):
        """Initialize repository.

        Args:
            file_path: Path to members JSON file. Defaults to settings.members_file
            cache_ttl: Cache time-to-live in seconds
        """
        self._file_path = file_path or settings.members_file
        self._cache_ttl = cache_ttl
        self._lock = threading.Lock()
        self._cache = {
            "loaded_at": 0.0,
            "mtime": None,
            "data": {"members": {}}
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
        """Load members data with caching.

        Returns:
            Dict containing members data with "members" key
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

            data = self._read_json_file(self._file_path, {"members": {}})
            if "members" not in data or not isinstance(data["members"], dict):
                data = {"members": {}}

            if not exists:
                self._atomic_write_json(self._file_path, data)

            self._cache.update({
                "loaded_at": now,
                "mtime": mtime,
                "data": data
            })
            return data

    def save(self, data: Dict[str, Any]) -> None:
        """Save members data and update cache.

        Args:
            data: Dict containing members data with "members" key
        """
        with self._lock:
            self._atomic_write_json(self._file_path, data)
            self._cache.update({
                "loaded_at": time.time(),
                "mtime": os.path.getmtime(self._file_path),
                "data": data
            })

    def get_all_members(self) -> Dict[str, Any]:
        """Get all members.

        Returns:
            Dict of member_key -> member_data
        """
        data = self.load()
        return data.get("members", {})

    def get_member(self, member_key: str) -> Optional[Dict[str, Any]]:
        """Get a specific member by key.

        Args:
            member_key: Member key (e.g., "ip:127.0.0.1" or "qq:123456")

        Returns:
            Member data dict or None if not found
        """
        members = self.get_all_members()
        return members.get(member_key)

    def add_member(self, member_key: str, member_data: Dict[str, Any]) -> None:
        """Add a new member.

        Args:
            member_key: Member key
            member_data: Member data dict
        """
        data = self.load()
        members = data.setdefault("members", {})
        members[member_key] = member_data
        self.save(data)

    def update_member(self, member_key: str, member_data: Dict[str, Any]) -> bool:
        """Update an existing member.

        Args:
            member_key: Member key
            member_data: Updated member data dict

        Returns:
            True if member existed and was updated, False otherwise
        """
        data = self.load()
        members = data.get("members", {})
        if member_key not in members:
            return False
        members[member_key] = member_data
        self.save(data)
        return True

    def delete_member(self, member_key: str) -> bool:
        """Delete a member.

        Args:
            member_key: Member key

        Returns:
            True if member existed and was deleted, False otherwise
        """
        data = self.load()
        members = data.get("members", {})
        if member_key not in members:
            return False
        del members[member_key]
        self.save(data)
        return True

    def batch_delete_members(self, member_keys: List[str]) -> int:
        """Delete multiple members.

        Args:
            member_keys: List of member keys to delete

        Returns:
            Number of members deleted
        """
        if not member_keys:
            return 0

        data = self.load()
        members = data.get("members", {})
        count = 0
        for key in member_keys:
            if key in members:
                del members[key]
                count += 1
        if count > 0:
            self.save(data)
        return count

    def batch_update_members(self, updates: Dict[str, Dict[str, Any]]) -> int:
        """Update multiple members.

        Args:
            updates: Dict of member_key -> member_data

        Returns:
            Number of members updated
        """
        if not updates:
            return 0

        data = self.load()
        members = data.get("members", {})
        count = 0
        for key, member_data in updates.items():
            if key in members:
                members[key] = member_data
                count += 1
        if count > 0:
            self.save(data)
        return count

    def ensure_member_exists(
        self,
        member_key: str,
        name: Optional[str] = None,
        tier: str = "permanent",
        enabled: bool = True
    ) -> Dict[str, Any]:
        """Ensure a member exists, creating if necessary.

        Args:
            member_key: Member key
            name: Member name
            tier: Member tier (permanent, month, normal)
            enabled: Whether member is enabled

        Returns:
            Member data dict
        """
        data = self.load()
        members = data.setdefault("members", {})

        if member_key in members:
            return members[member_key]

        now = datetime.now(timezone.utc)
        members[member_key] = {
            "name": (name or "").strip(),
            "tier": tier,
            "enabled": enabled,
            "expires_at": "",
            "updated_at": self._format_iso(now),
            "created_at": self._format_iso(now),
        }
        self.save(data)
        return members[member_key]

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
_member_repository: Optional[MemberRepository] = None


def get_member_repository() -> MemberRepository:
    """Get or create global member repository instance.

    Returns:
        MemberRepository instance
    """
    global _member_repository
    if _member_repository is None:
        _member_repository = MemberRepository()
    return _member_repository
