import json
import os
import tempfile
import re
from datetime import datetime, timedelta, timezone

from config import settings
from data.usage_repository import UsageRepository


def main():
    tz_raw = (settings.usage_time_zone or "").strip()
    tz = timezone.utc
    if tz_raw:
        lowered = tz_raw.lower()
        if lowered in {"asia/shanghai", "beijing", "bj", "utc+8", "utc+08", "utc+08:00", "gmt+8", "gmt+08", "gmt+08:00", "+08:00", "+0800", "+8"}:
            tz = timezone(timedelta(hours=8))
        else:
            m = re.match(r"^(?:utc|gmt)?\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?$", lowered)
            if m:
                sign = -1 if m.group(1) == "-" else 1
                hours = int(m.group(2))
                minutes = int(m.group(3) or 0)
                tz = timezone(sign * timedelta(hours=hours, minutes=minutes))
    now_utc = datetime.now(timezone.utc)
    now_local = now_utc.astimezone(tz)
    today = now_local.date().isoformat()
    ym = f"{now_local.year:04d}-{now_local.month:02d}"
    yesterday = (now_local - timedelta(days=1)).date().isoformat()
    last_month_dt = (now_local.replace(day=1) - timedelta(days=1))
    last_month_ym = f"{last_month_dt.year:04d}-{last_month_dt.month:02d}"

    with tempfile.TemporaryDirectory() as tmpdir:
        file_path = os.path.join(tmpdir, "usage_counters.json")
        payload = {
            "counters": {
                "ip:1.1.1.1": {
                    "daily_date": yesterday,
                    "daily_count": 5,
                    "monthly_ym": last_month_ym,
                    "monthly_count": 99,
                    "total_count": 5,
                    "error_count": 2,
                },
                "ip:2.2.2.2": {
                    "daily_date": today,
                    "daily_count": 7,
                    "monthly_ym": ym,
                    "monthly_count": 11,
                    "total_count": 7,
                    "error_count": 3,
                },
            }
        }
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)

        repo = UsageRepository(file_path=file_path, cache_ttl=0.0)
        c1 = repo.get_counter("ip:1.1.1.1", now=now_utc)
        assert c1["daily_date"] == today
        assert c1["monthly_ym"] == ym
        assert c1["daily_count"] == 0
        assert c1["monthly_count"] == 0
        assert c1["total_count"] == 0
        assert c1["error_count"] == 0

        c2 = repo.get_counter("ip:2.2.2.2", now=now_utc)
        assert c2["daily_date"] == today
        assert c2["monthly_ym"] == ym
        assert c2["daily_count"] == 7
        assert c2["monthly_count"] == 11
        assert c2["total_count"] == 7
        assert c2["error_count"] == 3

        before = repo.get_counter("ip:2.2.2.2", now=now_utc)
        repo.increment_usage("ip:2.2.2.2", now_utc, daily_limit=None, monthly_limit=None, is_error=True, increment_call=False)
        after = repo.get_counter("ip:2.2.2.2", now=now_utc)
        assert after["total_count"] == before["total_count"]
        assert after["daily_count"] == before["daily_count"]
        assert after["monthly_count"] == before["monthly_count"]
        assert after["error_count"] == before["error_count"] + 1

        print({"yesterday_record_view": c1, "today_record_view": c2})


if __name__ == "__main__":
    main()
