"""Renewal request data repository.

Handles all data access operations for renewal requests including CRUD,
batch operations, and caching.
"""
import json
import os
import threading
import time
from datetime import datetime, timezone
from typing import Dict, List, Optional, Any
from config import settings


class RenewalRepository:
    """Repository for renewal request data persistence and caching."""

    def __init__(self, file_path: Optional[str] = None, cache_ttl: float = 2.0):
        """Initialize repository.

        Args:
            file_path: Path to renewal requests JSON file. Defaults to settings.renewal_requests_file
            cache_ttl: Cache time-to-live in seconds
        """
        self._file_path = file_path or settings.renewal_requests_file
        self._cache_ttl = cache_ttl
        self._lock = threading.Lock()
        self._cache = {
            "loaded_at": 0.0,
            "mtime": None,
            "data": {"requests": []}
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
        """Load renewal requests data with caching.

        Returns:
            Dict containing renewal requests data with "requests" key
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

            data = self._read_json_file(self._file_path, {"requests": []})
            if "requests" not in data or not isinstance(data["requests"], list):
                data = {"requests": []}

            if not exists:
                self._atomic_write_json(self._file_path, data)

            self._cache.update({
                "loaded_at": now,
                "mtime": mtime,
                "data": data
            })
            return data

    def save(self, data: Dict[str, Any]) -> None:
        """Save renewal requests data and update cache.

        Args:
            data: Dict containing renewal requests data with "requests" key
        """
        with self._lock:
            self._atomic_write_json(self._file_path, data)
            self._cache.update({
                "loaded_at": time.time(),
                "mtime": os.path.getmtime(self._file_path),
                "data": data
            })

    def get_all_requests(self) -> List[Dict[str, Any]]:
        """Get all renewal requests.

        Returns:
            List of renewal request dicts
        """
        data = self.load()
        return data.get("requests", [])

    def get_pending_requests(self) -> List[Dict[str, Any]]:
        """Get pending renewal requests.

        Returns:
            List of pending renewal request dicts
        """
        requests = self.get_all_requests()
        return [r for r in requests if r.get("status") == "pending"]

    def add_request(self, request_data: Dict[str, Any]) -> None:
        """Add a new renewal request.

        Args:
            request_data: Renewal request data dict
        """
        data = self.load()
        requests = data.setdefault("requests", [])
        requests.append(request_data)
        self.save(data)

    def update_request(self, request_id: str, updates: Dict[str, Any]) -> bool:
        """Update a renewal request.

        Args:
            request_id: Request ID
            updates: Dict of fields to update

        Returns:
            True if request existed and was updated, False otherwise
        """
        data = self.load()
        requests = data.get("requests", [])
        for i, req in enumerate(requests):
            if req.get("id") == request_id:
                requests[i].update(updates)
                self.save(data)
                return True
        return False

    def delete_request(self, request_id: str) -> bool:
        """Delete a renewal request.

        Args:
            request_id: Request ID

        Returns:
            True if request existed and was deleted, False otherwise
        """
        data = self.load()
        requests = data.get("requests", [])
        original_length = len(requests)
        data["requests"] = [r for r in requests if r.get("id") != request_id]
        if len(data["requests"]) < original_length:
            self.save(data)
            return True
        return False

    def batch_update_requests(self, request_ids: List[str], updates: Dict[str, Any]) -> int:
        """Update multiple renewal requests.

        Args:
            request_ids: List of request IDs to update
            updates: Dict of fields to update

        Returns:
            Number of requests updated
        """
        if not request_ids:
            return 0

        data = self.load()
        requests = data.get("requests", [])
        id_set = set(request_ids)
        count = 0

        for i, req in enumerate(requests):
            if req.get("id") in id_set:
                requests[i].update(updates)
                count += 1

        if count > 0:
            self.save(data)

        return count

    def batch_delete_requests(self, request_ids: List[str]) -> int:
        """Delete multiple renewal requests.

        Args:
            request_ids: List of request IDs to delete

        Returns:
            Number of requests deleted
        """
        if not request_ids:
            return 0

        data = self.load()
        requests = data.get("requests", [])
        id_set = set(request_ids)
        original_length = len(requests)
        data["requests"] = [r for r in requests if r.get("id") not in id_set]

        deleted = original_length - len(data["requests"])
        if deleted > 0:
            self.save(data)

        return deleted

    def clear_cache(self) -> None:
        """Clear the data cache."""
        with self._lock:
            self._cache["loaded_at"] = 0.0
            self._cache["mtime"] = None


# Global repository instance
_renewal_repository: Optional[RenewalRepository] = None


def get_renewal_repository() -> RenewalRepository:
    """Get or create global renewal repository instance.

    Returns:
        RenewalRepository instance
    """
    global _renewal_repository
    if _renewal_repository is None:
        _renewal_repository = RenewalRepository()
    return _renewal_repository
