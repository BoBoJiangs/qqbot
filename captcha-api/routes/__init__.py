"""API routes layer for Captcha API.

This module provides route handlers organized by functionality.
"""
import sys
import os
# Add parent directory to path for sibling imports
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(current_dir)
if parent_dir not in sys.path:
    sys.path.insert(0, parent_dir)

from .admin_routes import admin_routes
from .member_routes import member_routes
from .renewal_routes import renewal_routes
from .api_routes import api_routes

__all__ = [
    "admin_routes",
    "member_routes",
    "renewal_routes",
    "api_routes",
]
