"""Configuration management for Captcha API.

Centralized configuration using pydantic-settings for type safety
and environment variable support.
"""
from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict
import os


class Settings(BaseSettings):
    """Application settings with environment variable support."""

    # File paths
    members_file: str = "members.json"
    usage_counters_file: str = "usage_counters.json"
    renewal_requests_file: str = "renewal_requests.json"
    admin_credentials_file: str = "admin_credentials.json"
    admin_secret_file: str = "admin_secret.txt"
    admin_settings_file: str = "admin_settings.json"
    allowed_ips_file: str = "allowed_ips.txt"
    allowed_qq_file: str = "allowed_qq.txt"

    # Model paths
    yolo_config: str = 'models/yolov4-tiny-dota2.cfg'
    yolo_weights: str = 'models/yolov4-tiny-dota2_last.weights'
    classify_config: str = 'models/darknet.cfg'
    classify_weights: str = 'models/darknet_last.weights'
    classify_labels: str = 'models/labels.txt'

    # Error image directory
    error_dir: str = "errorPic"

    # Image download settings
    image_max_bytes: int = 5000000
    image_max_redirects: int = 3
    image_connect_timeout_seconds: float = 3.0
    image_read_timeout_seconds: float = 7.0

    # Whitelist settings
    whitelist_cache_ttl_seconds: float = 60.0  # 增加到60秒，减少文件读取
    show_whitelist_details: bool = False

    # Admin settings
    admin_session_hours: float = 12.0
    admin_local_only: bool = False

    # Member limits
    normal_daily_limit: int = 20
    month_card_monthly_limit: int = 0
    default_month_card_valid_days: int = 30

    # Logging
    log_level: str = "INFO"

    # Cache TTL for data files (seconds)
    data_cache_ttl: float = 30.0  # 增加到30秒，减少文件读取

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        env_prefix=""
    )


def get_settings() -> Settings:
    """Get application settings instance."""
    return Settings()


# Global settings instance
settings = get_settings()
