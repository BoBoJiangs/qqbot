"""Member business logic service.

Handles member-related business rules including tier validation,
expiry checking, and quota management.
"""
import logging
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Optional, Any, Tuple
from data.member_repository import get_member_repository
from data.usage_repository import get_usage_repository
from config import settings


logger = logging.getLogger(__name__)


class MemberService:
    """Service for member business logic."""

    def __init__(self):
        """Initialize member service."""
        self._member_repo = get_member_repository()
        self._usage_repo = get_usage_repository()

    def get_all_members(self) -> Dict[str, Any]:
        """Get all members.

        Returns:
            Dict of member_key -> member_data
        """
        return self._member_repo.get_all_members()

    def get_member(self, member_key: str) -> Optional[Dict[str, Any]]:
        """Get a specific member.

        Args:
            member_key: Member key (e.g., "ip:127.0.0.1" or "qq:123456")

        Returns:
            Member data dict or None
        """
        return self._member_repo.get_member(member_key)

    def create_member(
        self,
        member_key: str,
        name: str = "",
        tier: str = "normal",
        enabled: bool = True,
        expires_at: str = "",
        daily_limit: Optional[int] = None
    ) -> Dict[str, Any]:
        """Create a new member.

        Args:
            member_key: Member key
            name: Member name
            tier: Member tier (permanent, month, normal)
            enabled: Whether enabled
            expires_at: Expiry date as ISO string
            daily_limit: Daily call limit (for normal tier)

        Returns:
            Created member data
        """
        now = datetime.now(timezone.utc)
        member_data = {
            "name": name,
            "tier": tier,
            "enabled": enabled,
            "expires_at": expires_at,
            "updated_at": self._format_iso(now),
            "created_at": self._format_iso(now),
        }

        if daily_limit is not None and tier == "normal":
            member_data["daily_limit"] = daily_limit

        self._member_repo.add_member(member_key, member_data)
        return member_data

    def update_member(
        self,
        member_key: str,
        name: Optional[str] = None,
        tier: Optional[str] = None,
        enabled: Optional[bool] = None,
        expires_at: Optional[str] = None,
        daily_limit: Optional[int] = None
    ) -> Optional[Dict[str, Any]]:
        """Update an existing member.

        Args:
            member_key: Member key
            name: New name (optional)
            tier: New tier (optional)
            enabled: New enabled status (optional)
            expires_at: New expiry date (optional)
            daily_limit: New daily limit (optional)

        Returns:
            Updated member data or None if not found
        """
        member = self._member_repo.get_member(member_key)
        if not member:
            return None

        now = datetime.now(timezone.utc)
        updates = {"updated_at": self._format_iso(now)}

        if name is not None:
            updates["name"] = name
        if tier is not None:
            updates["tier"] = tier
        if enabled is not None:
            updates["enabled"] = enabled
        if expires_at is not None:
            updates["expires_at"] = expires_at
        if daily_limit is not None:
            updates["daily_limit"] = daily_limit

        member.update(updates)
        self._member_repo.update_member(member_key, member)
        return member

    def delete_member(self, member_key: str) -> bool:
        """Delete a member.

        Args:
            member_key: Member key

        Returns:
            True if deleted, False if not found
        """
        return self._member_repo.delete_member(member_key)

    def batch_delete_members(self, member_keys: List[str]) -> int:
        """Delete multiple members.

        Args:
            member_keys: List of member keys

        Returns:
            Number of members deleted
        """
        return self._member_repo.batch_delete_members(member_keys)

    def batch_update_members(
        self,
        member_keys: List[str],
        enabled: Optional[bool] = None,
        tier: Optional[str] = None,
        extend_days: Optional[int] = None
    ) -> int:
        """Batch update members.

        Args:
            member_keys: List of member keys to update
            enabled: Set enabled status (optional)
            tier: Set tier (optional)
            extend_days: Extend expiry by N days (for month tier)

        Returns:
            Number of members updated
        """
        if not member_keys:
            return 0

        members = self._member_repo.get_all_members()
        updates = {}
        now = datetime.now(timezone.utc)

        for key in member_keys:
            if key not in members:
                continue

            member = members[key].copy()
            updated = False

            if enabled is not None:
                member["enabled"] = enabled
                updated = True

            if tier is not None:
                member["tier"] = tier
                updated = True

            if extend_days is not None and member.get("tier") == "month":
                current_expiry = self._parse_iso_datetime(member.get("expires_at"))
                if current_expiry and current_expiry > now:
                    new_expiry = current_expiry + timedelta(days=extend_days)
                else:
                    new_expiry = now + timedelta(days=extend_days)
                member["expires_at"] = self._format_iso(new_expiry)
                updated = True

            if updated:
                member["updated_at"] = self._format_iso(now)
                updates[key] = member

        if updates:
            return self._member_repo.batch_update_members(updates)
        return 0

    def is_member_expired(self, member: Dict[str, Any]) -> bool:
        """Check if a member is expired.

        Args:
            member: Member data dict

        Returns:
            True if expired, False otherwise
        """
        tier = member.get("tier", "permanent")
        if tier != "month":
            return False

        expires_at = self._parse_iso_datetime(member.get("expires_at"))
        if not expires_at:
            return False

        return datetime.now(timezone.utc) > expires_at

    def get_expiry_status(self, member: Dict[str, Any]) -> Tuple[str, str]:
        """Get human-readable expiry status.

        Args:
            member: Member data dict

        Returns:
            Tuple of (status_text, css_class)
            status_text: Human readable status
            css_class: CSS class for styling (ok, warning, expired)
        """
        tier = member.get("tier", "permanent")
        if tier != "month":
            return "永久有效", "ok"

        expires_at = self._parse_iso_datetime(member.get("expires_at"))
        if not expires_at:
            return "未设置", "warning"

        now = datetime.now(timezone.utc)
        days_remaining = (expires_at - now).days

        if days_remaining < 0:
            return f"已到期 ({self._format_bj(expires_at)})", "expired"
        elif days_remaining <= 3:
            return f"剩 {days_remaining} 天 ({self._format_bj(expires_at)})", "warning"
        elif days_remaining <= 7:
            return f"剩 {days_remaining} 天", "ok"
        else:
            return f"剩 {days_remaining} 天", "ok"

    def get_tier_label(self, tier: str) -> str:
        """Get human-readable tier label.

        Args:
            tier: Tier string

        Returns:
            Human-readable label
        """
        labels = {
            "permanent": "永久会员",
            "month": "月卡会员",
            "normal": "普通用户",
        }
        return labels.get(tier, tier)

    def ensure_member_from_whitelist(self, auth_type: str, auth_value: str, name: str = "") -> None:
        """Ensure member exists from whitelist entry.

        Args:
            auth_type: Either "ip" or "qq"
            auth_value: IP address or QQ number
            name: Member name from whitelist
        """
        member_key = f"{auth_type}:{auth_value}"
        self._member_repo.ensure_member_exists(member_key, name or "")

    @staticmethod
    def _parse_iso_datetime(value: Optional[str]) -> Optional[datetime]:
        """Parse ISO datetime string.

        Args:
            value: ISO datetime string

        Returns:
            Datetime object or None
        """
        if not value:
            return None
        try:
            dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(timezone.utc)
        except Exception:
            return None

    @staticmethod
    def _format_iso(dt: Optional[datetime]) -> str:
        """Format datetime as ISO string.

        Args:
            dt: Datetime object

        Returns:
            ISO formatted string
        """
        if not dt:
            return ""
        return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _format_bj(dt: datetime) -> str:
        """Format datetime as Beijing time string.

        Args:
            dt: Datetime object

        Returns:
            Beijing time formatted string
        """
        tz_bj = timezone(timedelta(hours=8))
        return dt.astimezone(tz_bj).strftime("%Y-%m-%d %H:%M:%S")

    def filter_members(
        self,
        tier: Optional[str] = None,
        enabled: Optional[bool] = None,
        search: Optional[str] = None,
        auth_type: Optional[str] = None
    ) -> List[Tuple[str, Dict[str, Any]]]:
        """Filter members by criteria.

        Args:
            tier: Filter by tier (optional)
            enabled: Filter by enabled status (optional)
            search: Search in name or key (optional)
            auth_type: Filter by auth type (ip/qq) (optional)

        Returns:
            List of (member_key, member_data) tuples
        """
        members = self.get_all_members()
        results = []

        for key, member in members.items():
            # Filter by tier
            if tier and member.get("tier") != tier:
                continue

            # Filter by enabled
            if enabled is not None and member.get("enabled") != enabled:
                continue

            # Filter by auth type
            if auth_type:
                if auth_type == "ip" and not key.startswith("ip:"):
                    continue
                if auth_type == "qq" and not key.startswith("qq:"):
                    continue

            # Search in name or key
            if search:
                search_lower = search.lower()
                name = member.get("name", "").lower()
                if search_lower not in name and search_lower not in key.lower():
                    continue

            results.append((key, member))

        return results

    def clear_cache(self) -> None:
        """Clear the member repository cache."""
        self._member_repo.clear_cache()


# Global service instance
_member_service: Optional[MemberService] = None


def get_member_service() -> MemberService:
    """Get or create global member service instance.

    Returns:
        MemberService instance
    """
    global _member_service
    if _member_service is None:
        _member_service = MemberService()
    return _member_service
