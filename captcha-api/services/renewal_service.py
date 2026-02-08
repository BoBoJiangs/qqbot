"""Renewal request business logic service.

Handles renewal request processing including approval, expiry calculation,
and status management.
"""
import logging
import secrets
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Optional, Any
from data.renewal_repository import get_renewal_repository
from data.member_repository import get_member_repository
from config import settings


logger = logging.getLogger(__name__)


class RenewalService:
    """Service for renewal request business logic."""

    def __init__(self):
        """Initialize renewal service."""
        self._renewal_repo = get_renewal_repository()
        self._member_repo = get_member_repository()

    def get_all_requests(self) -> List[Dict[str, Any]]:
        """Get all renewal requests.

        Returns:
            List of renewal request dicts
        """
        return self._renewal_repo.get_all_requests()

    def get_pending_requests(self) -> List[Dict[str, Any]]:
        """Get pending renewal requests.

        Returns:
            List of pending renewal request dicts
        """
        return self._renewal_repo.get_pending_requests()

    def create_request(
        self,
        member_key: str,
        member_name: str,
        qq_number: Optional[str] = None,
        days: int = 30
    ) -> Dict[str, Any]:
        """Create a new renewal request.

        Args:
            member_key: Member key (e.g., "qq:123456")
            member_name: Member name
            qq_number: QQ number (if applicable)
            days: Requested extension days

        Returns:
            Created renewal request data
        """
        now = datetime.now(timezone.utc)
        request_data = {
            "id": secrets.token_hex(8),
            "member_key": member_key,
            "member_name": member_name,
            "qq_number": qq_number,
            "requested_days": days,
            "status": "pending",
            "created_at": self._format_iso(now),
            "updated_at": self._format_iso(now),
        }

        self._renewal_repo.add_request(request_data)
        return request_data

    def approve_request(self, request_id: str, days: Optional[int] = None) -> bool:
        """Approve a renewal request.

        Args:
            request_id: Request ID
            days: Extension days (overrides requested_days if provided)

        Returns:
            True if approved, False if not found
        """
        requests = self._renewal_repo.get_all_requests()
        request = None
        for req in requests:
            if req.get("id") == request_id:
                request = req
                break

        if not request:
            return False

        # Get extension days
        extend_days = days if days is not None else request.get("requested_days", 30)

        # Update member expiry
        member_key = request.get("member_key")
        member = self._member_repo.get_member(member_key)

        if member:
            now = datetime.now(timezone.utc)
            current_expiry = self._parse_iso_datetime(member.get("expires_at"))

            # Calculate new expiry date
            if current_expiry and current_expiry > now:
                new_expiry = current_expiry + timedelta(days=extend_days)
            else:
                new_expiry = now + timedelta(days=extend_days)

            # Update member
            member["expires_at"] = self._format_iso(new_expiry)
            member["tier"] = "month"
            member["enabled"] = True
            member["updated_at"] = self._format_iso(now)

            self._member_repo.update_member(member_key, member)

        # Update request status
        self._renewal_repo.update_request(request_id, {
            "status": "approved",
            "approved_days": extend_days,
            "updated_at": self._format_iso(datetime.now(timezone.utc))
        })

        return True

    def ignore_request(self, request_id: str) -> bool:
        """Ignore a renewal request.

        Args:
            request_id: Request ID

        Returns:
            True if ignored, False if not found
        """
        return self._renewal_repo.update_request(request_id, {
            "status": "ignored",
            "updated_at": self._format_iso(datetime.now(timezone.utc))
        })

    def batch_approve_requests(
        self,
        request_ids: List[str],
        days: Optional[int] = None
    ) -> int:
        """Approve multiple renewal requests.

        Args:
            request_ids: List of request IDs
            days: Extension days (applies to all)

        Returns:
            Number of requests approved
        """
        if not request_ids:
            return 0

        count = 0
        for req_id in request_ids:
            if self.approve_request(req_id, days):
                count += 1

        return count

    def batch_ignore_requests(self, request_ids: List[str]) -> int:
        """Ignore multiple renewal requests.

        Args:
            request_ids: List of request IDs

        Returns:
            Number of requests ignored
        """
        return self._renewal_repo.batch_update_requests(
            request_ids,
            {
                "status": "ignored",
                "updated_at": self._format_iso(datetime.now(timezone.utc))
            }
        )

    def delete_request(self, request_id: str) -> bool:
        """Delete a renewal request.

        Args:
            request_id: Request ID

        Returns:
            True if deleted, False if not found
        """
        return self._renewal_repo.delete_request(request_id)

    def batch_delete_requests(self, request_ids: List[str]) -> int:
        """Delete multiple renewal requests.

        Args:
            request_ids: List of request IDs

        Returns:
            Number of requests deleted
        """
        return self._renewal_repo.batch_delete_requests(request_ids)

    def get_status_badge(self, status: str) -> tuple:
        """Get status badge CSS class and label.

        Args:
            status: Status string (pending, approved, ignored)

        Returns:
            Tuple of (css_class, label)
        """
        badges = {
            "pending": ("normal", "待处理"),
            "approved": ("enabled", "已批准"),
            "ignored": ("disabled", "已忽略"),
        }
        return badges.get(status, ("normal", status))

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


# Global service instance
_renewal_service: Optional[RenewalService] = None


def get_renewal_service() -> RenewalService:
    """Get or create global renewal service instance.

    Returns:
        RenewalService instance
    """
    global _renewal_service
    if _renewal_service is None:
        _renewal_service = RenewalService()
    return _renewal_service
