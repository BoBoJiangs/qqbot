"""Whitelist data repository.

Handles loading and caching of IP and QQ whitelists from text files.
"""
import os
import re
import threading
import time
import logging
from typing import Dict, Set, Tuple, Optional  # 确保导入 Optional
from config import settings


logger = logging.getLogger(__name__)


class WhitelistRepository:
    """Repository for IP and QQ whitelist data with caching."""

    def __init__(
        self,
        ips_file: Optional[str] = None,
        qq_file: Optional[str] = None,
        cache_ttl: Optional[float] = None
    ):
        """Initialize repository.

        Args:
            ips_file: Path to IP whitelist file
            qq_file: Path to QQ whitelist file
            cache_ttl: Cache time-to-live in seconds
        """
        self._ips_file = ips_file or settings.allowed_ips_file
        self._qq_file = qq_file or settings.allowed_qq_file
        self._cache_ttl = cache_ttl if cache_ttl is not None else settings.whitelist_cache_ttl_seconds
        self._lock = threading.Lock()
        self._cache = {
            "loaded_at": 0.0,
            "ips_mtime": None,
            "qq_mtime": None,
            "allowed_ips": set(),
            "allowed_qq": set(),
            "ip_info": {},
            "qq_info": {},
        }

        # Pattern for QQ lines: "用户名：QQ号#QQ号"
        self._qq_line_re = re.compile(r'^(.*?)[:：]\s*((?:\d+)(?:#\d+)*)#?$')

    def load_ip_whitelist(self) -> Tuple[Set[str], Dict[str, str]]:
        """Load IP whitelist from file.

        Returns:
            Tuple of (set of allowed IPs, dict of ip -> name)
        """
        allowed_ips = set()
        ip_info = {}

        if not os.path.exists(self._ips_file):
            return allowed_ips, ip_info

        with open(self._ips_file, "r", encoding="utf-8") as f:
            for line_num, raw_line in enumerate(f, 1):
                line = (raw_line or "").strip()
                if not line or line.startswith("#"):
                    continue

                name = ""
                ip_part = line
                if ":" in line or "：" in line:
                    sep = "：" if "：" in line else ":"
                    left, right = line.split(sep, 1)
                    name = left.strip()
                    ip_part = right.strip()

                candidates = re.split(r"[#,\s]+", ip_part)
                for candidate in candidates:
                    ip = (candidate or "").strip()
                    if not ip:
                        continue
                    allowed_ips.add(ip)
                    if name:
                        ip_info[ip] = name
                    elif ip not in ip_info:
                        ip_info[ip] = ""

        return allowed_ips, ip_info

    def load_qq_whitelist(self) -> Tuple[Set[str], Dict[str, str]]:
        """Load QQ whitelist from file.

        File format: "用户名：QQ号#QQ号"

        Returns:
            Tuple of (set of allowed QQ numbers, dict of qq -> name)
        """
        allowed_qq = set()
        qq_info = {}

        if not os.path.exists(self._qq_file):
            return allowed_qq, qq_info

        with open(self._qq_file, "r", encoding="utf-8") as f:
            for line_num, line in enumerate(f, 1):
                line = line.strip()
                if line and not line.startswith('#'):
                    # Handle plain QQ number
                    if line.isdigit():
                        allowed_qq.add(line)
                        qq_info[line] = ""
                        continue

                    # Handle "用户名：QQ号#QQ号" format
                    match = self._qq_line_re.match(line)
                    if match:
                        comment = match.group(1).strip()
                        qq_part = match.group(2).strip()

                        # Split QQ numbers by #
                        qq_numbers = [num for num in qq_part.split('#') if num]

                        for qq_num in qq_numbers:
                            if qq_num.isdigit():
                                allowed_qq.add(qq_num)
                                qq_info[qq_num] = comment
                            else:
                                logger.warning(f"第{line_num}行有无效的QQ号: {qq_num}")
                    else:
                        logger.warning(f"第{line_num}行格式不正确: {line}")

        logger.info(f"加载了 {len(allowed_qq)} 个QQ号")
        return allowed_qq, qq_info

    def get_whitelists(self) -> Tuple[Set[str], Set[str], Dict[str, str], Dict[str, str]]:
        """Get IP and QQ whitelists with caching.

        Returns:
            Tuple of (allowed_ips, allowed_qq, ip_info, qq_info)
        """
        now = time.time()
        ips_exists = os.path.exists(self._ips_file)
        qq_exists = os.path.exists(self._qq_file)
        ips_mtime = os.path.getmtime(self._ips_file) if ips_exists else None
        qq_mtime = os.path.getmtime(self._qq_file) if qq_exists else None

        # Check if cache is fresh
        is_fresh = (
            (now - self._cache["loaded_at"]) < self._cache_ttl
            and ips_mtime == self._cache["ips_mtime"]
            and qq_mtime == self._cache["qq_mtime"]
        )

        if is_fresh:
            return (
                self._cache["allowed_ips"],
                self._cache["allowed_qq"],
                self._cache["ip_info"],
                self._cache["qq_info"],
            )

        # Reload whitelists
        allowed_ips, ip_info = self.load_ip_whitelist()
        allowed_qq, qq_info = self.load_qq_whitelist()

        with self._lock:
            self._cache.update({
                "loaded_at": now,
                "ips_mtime": ips_mtime,
                "qq_mtime": qq_mtime,
                "allowed_ips": allowed_ips,
                "allowed_qq": allowed_qq,
                "ip_info": ip_info,
                "qq_info": qq_info,
            })

        return allowed_ips, allowed_qq, ip_info, qq_info

    def save_ip_whitelist(self, ip_info: Dict[str, str]) -> None:
        """Save IP whitelist to file.

        Args:
            ip_info: Dict of ip -> name
        """
        name_to_ips = {}
        unnamed_ips = []

        for ip, name in (ip_info or {}).items():
            ip = (ip or "").strip()
            if not ip:
                continue
            name = (name or "").strip()
            if not name:
                unnamed_ips.append(ip)
                continue
            name_to_ips.setdefault(name, []).append(ip)

        lines = [
            "# IP白名单文件，每行一个IP地址",
            "# 支持格式：用户名：IP 或 用户名:IP（也支持一行多个IP，用 # 或 , 分隔）",
            "# 该文件可手动编辑，也可通过 /admin/whitelist 页面维护",
            "",
        ]

        # Add named IPs (multiple IPs per line if same name)
        for name, ips in sorted(name_to_ips.items()):
            if len(ips) == 1:
                lines.append(f"{name}：{ips[0]}")
            else:
                lines.append(f"{name}：{'#'.join(ips)}")

        # Add unnamed IPs
        if unnamed_ips:
            lines.append("")
            lines.extend(unnamed_ips)

        content = "\n".join(lines) + "\n"
        self._atomic_write_text(self._ips_file, content)

        # Clear cache to force reload
        self.clear_cache()

    def save_qq_whitelist(self, qq_info: Dict[str, str]) -> None:
        """Save QQ whitelist to file.

        Args:
            qq_info: Dict of qq -> name
        """
        name_to_qqs = {}
        unnamed_qqs = []

        for qq, name in (qq_info or {}).items():
            qq = (qq or "").strip()
            if not qq or not qq.isdigit():
                continue
            name = (name or "").strip()
            if not name:
                unnamed_qqs.append(qq)
                continue
            name_to_qqs.setdefault(name, []).append(qq)

        lines = [
            "# QQ白名单文件格式：用户名：QQ号#QQ号",
            "# 该文件可手动编辑，也可通过 /admin/whitelist 页面维护",
            "",
        ]

        # Add named QQs (multiple QQs per line if same name)
        for name, qqs in sorted(name_to_qqs.items()):
            if len(qqs) == 1:
                lines.append(f"{name}：{qqs[0]}")
            else:
                lines.append(f"{name}：{'#'.join(qqs)}")

        # Add unnamed QQs
        if unnamed_qqs:
            lines.append("")
            lines.extend(unnamed_qqs)

        content = "\n".join(lines) + "\n"
        self._atomic_write_text(self._qq_file, content)

        # Clear cache to force reload
        self.clear_cache()

    def _atomic_write_text(self, path: str, content: str) -> None:
        """Atomically write text file."""
        import secrets
        directory = os.path.dirname(os.path.abspath(path)) or "."
        base = os.path.basename(path)
        tmp_path = os.path.join(directory, f".{base}.{secrets.token_hex(8)}.tmp")
        with open(tmp_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        os.replace(tmp_path, path)

    def initialize_files(self) -> None:
        """Initialize whitelist files with default content if they don't exist."""
        # Create IP whitelist file
        if not os.path.exists(self._ips_file):
            with open(self._ips_file, "w", encoding="utf-8") as f:
                f.write("# IP白名单文件，每行一个IP地址\n")
                f.write("# 支持格式：用户名：IP 或 用户名:IP（也支持一行多个IP，用 # 或 , 分隔）\n")
                f.write("# 示例：\n")
                f.write("本机：127.0.0.1\n")
                f.write("办公室：192.168.1.100#192.168.1.101\n")
            logger.info("已创建IP白名单文件: %s", self._ips_file)

        # Create QQ whitelist file
        if not os.path.exists(self._qq_file):
            with open(self._qq_file, "w", encoding="utf-8") as f:
                f.write("# QQ白名单文件格式：用户名：QQ号#QQ号\n")
                f.write("# 示例：\n")
                f.write("一心：819463350#1018454301\n")
                f.write("虎虎：1212222211#213321233\n")
            logger.info("已创建QQ白名单文件: %s", self._qq_file)

    def clear_cache(self) -> None:
        """Clear the whitelist cache."""
        with self._lock:
            self._cache["loaded_at"] = 0.0
            self._cache["ips_mtime"] = None
            self._cache["qq_mtime"] = None


# Global repository instance
_whitelist_repository: Optional[WhitelistRepository] = None


def get_whitelist_repository() -> WhitelistRepository:
    """Get or create global whitelist repository instance.

    Returns:
        WhitelistRepository instance
    """
    global _whitelist_repository
    if _whitelist_repository is None:
        _whitelist_repository = WhitelistRepository()
        _whitelist_repository.initialize_files()
    return _whitelist_repository
