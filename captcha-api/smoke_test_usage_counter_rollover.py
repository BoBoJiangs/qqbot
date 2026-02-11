import json
import os
import tempfile
from datetime import datetime, timedelta, timezone

from data.usage_repository import UsageRepository


def main():
    now = datetime.now(timezone.utc)
    today = now.date().isoformat()
    ym = f"{now.year:04d}-{now.month:02d}"
    yesterday = (now - timedelta(days=1)).date().isoformat()
    last_month_dt = (now.replace(day=1) - timedelta(days=1))
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
        c1 = repo.get_counter("ip:1.1.1.1", now=now)
        assert c1["daily_date"] == today
        assert c1["monthly_ym"] == ym
        assert c1["daily_count"] == 0
        assert c1["monthly_count"] == 0
        assert c1["total_count"] == 0
        assert c1["error_count"] == 0

        c2 = repo.get_counter("ip:2.2.2.2", now=now)
        assert c2["daily_date"] == today
        assert c2["monthly_ym"] == ym
        assert c2["daily_count"] == 7
        assert c2["monthly_count"] == 11
        assert c2["total_count"] == 7
        assert c2["error_count"] == 3

        print({"yesterday_record_view": c1, "today_record_view": c2})


if __name__ == "__main__":
    main()

