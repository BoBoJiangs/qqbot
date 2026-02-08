"""Admin UI components for Captcha API.

Provides HTML/CSS templates for the admin management interface
with SweetAlert2 integration for beautiful notifications.
"""
from fastapi.responses import HTMLResponse


class AdminUI:
    """Admin UI renderer with SweetAlert2 integration."""

    def __init__(self):
        """Initialize AdminUI with CSS styles."""

        # Fira Sans & Fira Code Fonts for dashboard aesthetic
        self._google_fonts = '<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin><link href="https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500;600&family=Fira+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">'

        self._admin_css = """
        <style>
          :root {
            /* Design System Colors - OLED Dark Mode */
            --bg-primary: #020617;
            --bg-secondary: #0F172A;
            --bg-tertiary: #1E293B;
            --text-primary: #F8FAFC;
            --text-secondary: #94A3B8;
            --text-accent: #CBD5E1;
            --border-subtle: rgba(148,163,184,.12);
            --border-default: rgba(148,163,184,.18);

            /* Semantic Colors */
            --success: #22C55E;
            --success-bg: rgba(34,197,94,.15);
            --success-border: rgba(34,197,94,.4);
            --danger: #EF4444;
            --danger-bg: rgba(239,68,68,.15);
            --danger-border: rgba(239,68,68,.4);
            --warning: #F59E0B;
            --info: #22D3EE;
            --info-bg: rgba(34,211,238,.15);
            --info-border: rgba(34,211,238,.4);

            /* Brand Gradient */
            --brand-gradient: linear-gradient(135deg, #22D3EE 0%, #60A5FA 100%);
            --brand-glow: rgba(34,211,238,.3);

            /* Shadows */
            --shadow-sm: 0 1px 3px rgba(0,0,0,.3);
            --shadow-md: 0 4px 12px rgba(0,0,0,.4);
            --shadow-lg: 0 12px 32px rgba(0,0,0,.5);
            --shadow-glow: 0 0 20px var(--brand-glow);

            /* Timing - Optimized for micro-interactions */
            --duration-instant: 100ms;
            --duration-fast: 150ms;
            --duration-normal: 200ms;
            --duration-slow: 300ms;
            --ease-out: cubic-bezier(0, 0, 0.2, 1);
            --ease-in-out: cubic-bezier(0.4, 0, 0.2, 1);
          }

          /* Base - Reset & Typography */
          *, *::before, *::after {
            box-sizing: border-box;
          }

          html {
            font-size: 16px;
            -webkit-font-smoothing: antialiased;
            -moz-osx-font-smoothing: grayscale;
          }

          body {
            margin: 0;
            font-family: 'Fira Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            font-size: 0.875rem;
            line-height: 1.6;
            color: var(--text-primary);
            background:
              radial-gradient(ellipse 120% 80% at 20% 0%, rgba(34,211,238,.06) 0%, transparent 50%),
              radial-gradient(ellipse 120% 80% at 80% 100%, rgba(96,165,250,.06) 0%, transparent 50%),
              var(--bg-primary);
            min-height: 100vh;
          }

          /* Focus Styles - Keyboard Navigation */
          :focus-visible {
            outline: 2px solid #22D3EE;
            outline-offset: 2px;
            border-radius: 4px;
          }

          /* Links */
          a {
            color: #93C5FD;
            text-decoration: none;
            transition: color var(--duration-fast) var(--ease-out);
            cursor: pointer;
          }

          a:hover {
            color: #BFDBFE;
          }

          /* Layout */
          .app {
            display: flex;
            min-height: 100vh;
          }

          /* Sidebar */
          .sidebar {
            width: 260px;
            padding: 1.5rem 1rem;
            border-right: 1px solid var(--border-subtle);
            background: linear-gradient(180deg, rgba(15,23,42,.95) 0%, rgba(2,6,23,.8) 100%);
            position: sticky;
            top: 0;
            height: 100vh;
            overflow-y: auto;
            backdrop-filter: blur(12px);
            z-index: 20;
          }

          /* Brand Section */
          .brand {
            display: flex;
            align-items: center;
            gap: 0.875rem;
            padding: 0.875rem 1rem;
            border-radius: 0.75rem;
            background: rgba(15,23,42,.5);
            border: 1px solid var(--border-subtle);
            margin-bottom: 0.5rem;
            transition: all var(--duration-normal) var(--ease-out);
            cursor: pointer;
          }

          .brand:hover {
            border-color: rgba(96,165,250,.3);
            box-shadow: var(--shadow-md), 0 0 0 1px rgba(96,165,250,.1);
          }

          .logo {
            width: 2.625rem;
            height: 2.625rem;
            border-radius: 0.75rem;
            background: var(--brand-gradient);
            box-shadow: 0 4px 16px var(--brand-glow);
            flex-shrink: 0;
          }

          .brand-title {
            font-weight: 700;
            font-size: 1rem;
            letter-spacing: 0.02em;
            background: var(--brand-gradient);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
          }

          .brand-sub {
            font-size: 0.75rem;
            color: var(--text-secondary);
            margin-top: 0.125rem;
            font-weight: 500;
            letter-spacing: 0.01em;
          }

          /* Navigation */
          .nav {
            margin-top: 1.25rem;
            display: flex;
            flex-direction: column;
            gap: 0.25rem;
          }

          .nav a {
            display: flex;
            align-items: center;
            padding: 0.75rem 1rem;
            border-radius: 0.625rem;
            color: var(--text-accent);
            font-weight: 500;
            font-size: 0.875rem;
            transition: all var(--duration-normal) var(--ease-out);
            position: relative;
            cursor: pointer;
          }

          .nav a::before {
            content: '';
            position: absolute;
            left: 0;
            top: 50%;
            transform: translateY(-50%) scaleY(0);
            width: 3px;
            height: 60%;
            background: var(--brand-gradient);
            border-radius: 0 2px 2px 0;
            transition: transform var(--duration-normal) var(--ease-out);
          }

          .nav a:hover {
            background: rgba(30,41,59,.5);
            color: var(--text-primary);
            transform: translateX(2px);
          }

          .nav a:hover::before {
            transform: translateY(-50%) scaleY(1);
          }

          .nav a.active {
            background: rgba(59,130,246,.15);
            color: #93C5FD;
            border: 1px solid rgba(96,165,250,.25);
          }

          .nav a.active::before {
            transform: translateY(-50%) scaleY(1);
          }

          /* Main Content */
          .main {
            flex: 1;
            padding: 1.5rem 1.5rem 3rem 1.5rem;
            max-width: 1600px;
            margin: 0 auto;
            width: 100%;
            overflow-x: hidden;
          }

          /* Header */
          .header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 1rem;
            padding: 1rem 1.25rem;
            border-radius: 1rem;
            background: rgba(15,23,42,.6);
            border: 1px solid var(--border-subtle);
            backdrop-filter: blur(12px);
            margin-bottom: 1.25rem;
          }

          .title {
            font-size: 1.125rem;
            font-weight: 700;
            letter-spacing: 0.02em;
          }

          /* Content Area */
          .content {
            animation: fadeIn var(--duration-slow) var(--ease-out);
          }

          /* Cards Grid */
          .row {
            display: flex;
            gap: 1rem;
            flex-wrap: wrap;
          }

          .row > * {
            flex: 1 1 340px;
            min-width: 0;
          }

          /* Card Component */
          .card {
            background: rgba(15,23,42,.6);
            border: 1px solid var(--border-subtle);
            border-radius: 1rem;
            padding: 1.25rem;
            box-shadow: var(--shadow-sm);
            transition: all var(--duration-normal) var(--ease-out);
            backdrop-filter: blur(12px);
            animation: slideInUp var(--duration-slow) var(--ease-out);
            animation-fill-mode: both;
          }

          .card:nth-child(1) { animation-delay: 50ms; }
          .card:nth-child(2) { animation-delay: 100ms; }
          .card:nth-child(3) { animation-delay: 150ms; }

          .card:hover {
            transform: translateY(-2px);
            box-shadow: var(--shadow-lg);
            border-color: rgba(96,165,250,.2);
          }

          /* Typography */
          h2 {
            margin: 0 0 1rem 0;
            font-size: 0.875rem;
            color: var(--text-accent);
            letter-spacing: 0.02em;
            font-weight: 600;
          }

          label {
            display: block;
            font-size: 0.8125rem;
            color: var(--text-secondary);
            margin-top: 0.75rem;
            font-weight: 500;
            letter-spacing: 0.01em;
          }

          /* Form Elements */
          input, select, textarea {
            width: 100%;
            padding: 0.75rem 0.875rem;
            border-radius: 0.75rem;
            border: 1px solid var(--border-default);
            background: rgba(2,6,23,.6);
            color: var(--text-primary);
            outline: none;
            font-size: 0.875rem;
            font-family: inherit;
            transition: all var(--duration-fast) var(--ease-out);
          }

          input:hover, select:hover, textarea:hover {
            border-color: rgba(148,163,184,.3);
          }

          input:focus, select:focus, textarea:focus {
            border-color: rgba(96,165,250,.6);
            box-shadow: 0 0 0 3px rgba(59,130,246,.15);
          }

          textarea {
            min-height: 6rem;
            resize: vertical;
          }

          /* Select Dropdown Enhancement */
          select {
            appearance: none;
            -webkit-appearance: none;
            -moz-appearance: none;
            background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2394A3B8' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
            background-repeat: no-repeat;
            background-position: right 0.875rem center;
            padding-right: 2.5rem;
            cursor: pointer;
            position: relative;
          }

          select:hover {
            background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2360A5FA' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
          }

          select:focus {
            background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2322D3EE' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
          }

          /* Option Styling */
          select option {
            background: #0F172A;
            color: #F8FAFC;
            padding: 0.5rem 0.75rem;
            border: none;
          }

          select option:hover,
          select option:focus {
            background: rgba(34, 211, 238, 0.15);
            color: #22D3EE;
          }

          select option:checked {
            background: linear-gradient(135deg, rgba(34, 211, 238, 0.25), rgba(96, 165, 250, 0.2));
            color: #22D3EE;
            font-weight: 600;
          }

          /* Buttons */
          button, .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 0.5rem;
            padding: 0.75rem 1.125rem;
            border-radius: 0.75rem;
            border: 1px solid var(--border-default);
            background: linear-gradient(180deg, rgba(30,41,59,.9), rgba(2,6,23,.7));
            color: var(--text-primary);
            cursor: pointer;
            font-weight: 600;
            font-size: 0.875rem;
            font-family: inherit;
            transition: all var(--duration-fast) var(--ease-out);
          }

          button:hover, .btn:hover {
            border-color: rgba(96,165,250,.5);
            background: linear-gradient(180deg, rgba(30,41,59,1), rgba(2,6,23,.8));
            transform: translateY(-1px);
            box-shadow: var(--shadow-md);
          }

          button:active, .btn:active {
            transform: translateY(0);
            transition: transform var(--duration-instant) var(--ease-out);
          }

          button:focus-visible, .btn:focus-visible {
            outline: 2px solid #22D3EE;
            outline-offset: 2px;
          }

          .btn-sm {
            padding: 0.5rem 0.875rem;
            border-radius: 0.625rem;
            font-size: 0.8125rem;
          }

          .btn-danger {
            border-color: var(--danger-border);
            background: var(--danger-bg);
            color: #FCA5A5;
          }

          .btn-danger:hover {
            border-color: rgba(239,68,68,.6);
            background: rgba(239,68,68,.25);
          }

          /* Status Badge */
          .muted {
            color: var(--text-secondary);
            font-size: 0.8125rem;
          }

          .msg {
            padding: 0.875rem 1.125rem;
            border-radius: 0.75rem;
            background: rgba(2,6,23,.6);
            border: 1px solid var(--border-default);
            margin: 1rem 0;
          }

          /* Table */
          .table-wrapper {
            overflow-x: auto;
            border-radius: 1rem;
            animation: fadeIn var(--duration-slow) var(--ease-out);
          }

          table {
            width: 100%;
            border-collapse: separate;
            border-spacing: 0;
            overflow: hidden;
            border-radius: 1rem;
            border: 1px solid var(--border-subtle);
            background: rgba(2,6,23,.4);
          }

          thead th {
            font-size: 0.75rem;
            color: #A5B4FC;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            padding: 1rem 0.875rem;
            background: rgba(15,23,42,.7);
            border-bottom: 1px solid var(--border-subtle);
            text-align: left;
            font-weight: 700;
            white-space: nowrap;
          }

          tbody td {
            padding: 0.75rem 0.875rem;
            border-bottom: 1px solid rgba(148,163,184,.08);
            font-size: 0.875rem;
            color: var(--text-accent);
            text-align: left;
            vertical-align: middle;
            transition: background-color var(--duration-fast) var(--ease-out);
          }

          tbody tr:hover td {
            background: rgba(30,41,59,.4);
          }

          tbody tr:last-child td {
            border-bottom: none;
          }

          /* Form Components */
          form.inline {
            display: inline;
          }

          .actions {
            display: inline-flex;
            gap: 0.375rem;
            align-items: center;
            flex-wrap: nowrap;
          }

          .actions form {
            margin: 0;
          }

          .form-group {
            background: rgba(2,6,23,.4);
            border: 1px solid var(--border-subtle);
            border-radius: 0.875rem;
            padding: 1.25rem;
            margin-bottom: 1.25rem;
            transition: border-color var(--duration-normal) var(--ease-out);
          }

          .form-group:hover {
            border-color: rgba(96,165,250,.15);
          }

          .form-group h3 {
            margin: 0 0 1rem 0;
            font-size: 0.875rem;
            color: #A5B4FC;
            display: flex;
            align-items: center;
            gap: 0.625rem;
            font-weight: 600;
          }

          .form-group h3::before {
            content: '';
            width: 3px;
            height: 1rem;
            background: var(--brand-gradient);
            border-radius: 2px;
          }

          .checkbox-wrapper {
            display: flex;
            align-items: center;
            gap: 0.625rem;
            padding: 0.5rem 0;
            cursor: pointer;
          }

          input[type="checkbox"] {
            width: 1.125rem;
            height: 1.125rem;
            accent-color: #22D3EE;
            cursor: pointer;
          }

          /* Batch Actions */
          .batch-actions {
            margin-top: 1rem;
            padding: 1rem;
            background: rgba(15,23,42,.5);
            border-radius: 0.875rem;
            border: 1px solid var(--border-subtle);
            animation: fadeIn var(--duration-slow) var(--ease-out);
          }

          /* Validation Messages */
          .validation-msg {
            padding: 0.75rem 1rem;
            border-radius: 0.625rem;
            font-size: 0.8125rem;
            margin-top: 0.625rem;
            display: none;
            animation: slideInUp var(--duration-normal) var(--ease-out);
          }

          .validation-msg.error {
            background: var(--danger-bg);
            border: 1px solid var(--danger-border);
            color: #FCA5A5;
          }

          .validation-msg.success {
            background: var(--success-bg);
            border: 1px solid var(--success-border);
            color: #86EFAC;
          }

          .validation-msg.show {
            display: block;
          }

          /* Status Badges */
          .status-badge {
            display: inline-flex;
            align-items: center;
            padding: 0.3125rem 0.625rem;
            border-radius: 0.5rem;
            font-size: 0.6875rem;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.04em;
            transition: all var(--duration-fast) var(--ease-out);
            cursor: default;
            white-space: nowrap;
            line-height: 1.2;
          }

          .status-badge:hover {
            transform: scale(1.05);
          }

          .status-badge.enabled {
            background: var(--success-bg);
            border: 1px solid var(--success-border);
            color: #86EFAC;
          }

          .status-badge.disabled {
            background: var(--danger-bg);
            border: 1px solid var(--danger-border);
            color: #FCA5A5;
          }

          .status-badge.normal {
            background: rgba(30,41,59,.6);
            border: 1px solid var(--border-default);
            color: var(--text-secondary);
          }

          .status-badge.month {
            background: var(--info-bg);
            border: 1px solid var(--info-border);
            color: #93C5FD;
          }

          .status-badge.permanent {
            background: rgba(168,85,247,.15);
            border: 1px solid rgba(192,132,252,.4);
            color: #C4B5FD;
          }

          /* Expiry Status */
          .expiry-warning { color: var(--warning); font-weight: 600; }
          .expiry-expired { color: var(--danger); font-weight: 600; }
          .expiry-ok { color: var(--success); font-weight: 600; }

          /* Number Input Wrapper */
          .number-input-wrapper {
            display: inline-flex;
            align-items: center;
            gap: 0.375rem;
          }

          .number-input-wrapper input {
            width: 4rem;
            padding: 0.5rem 0.625rem;
            text-align: center;
          }

          /* Animations */
          @keyframes fadeIn {
            from { opacity: 0; }
            to { opacity: 1; }
          }

          @keyframes slideInUp {
            from {
              opacity: 0;
              transform: translateY(12px);
            }
            to {
              opacity: 1;
              transform: translateY(0);
            }
          }

          /* Reduced Motion - Accessibility */
          @media (prefers-reduced-motion: reduce) {
            *,
            *::before,
            *::after {
              animation-duration: 0.01ms !important;
              animation-iteration-count: 1 !important;
              transition-duration: 0.01ms !important;
            }

            .card:hover {
              transform: none;
            }

            button:hover, .btn:hover {
              transform: none;
            }

            .nav a:hover {
              transform: none;
            }
          }

          /* Responsive */
          @media (max-width: 860px) {
            .app {
              flex-direction: column;
            }

            .sidebar {
              width: 100%;
              height: auto;
              position: static;
              border-right: none;
              border-bottom: 1px solid var(--border-subtle);
            }

            .main {
              padding: 1.25rem 1rem 2rem 1rem;
            }

            .row > * {
              flex: 1 1 280px;
            }

            table {
              font-size: 0.8125rem;
            }

            thead th, tbody td {
              padding: 0.75rem 0.5rem;
            }
          }

          /* SweetAlert2 Theme */
          .swal2-popup {
            background: rgba(15,23,42,.98) !important;
            border: 1px solid var(--border-subtle) !important;
            border-radius: 1rem !important;
            backdrop-filter: blur(12px) !important;
            box-shadow: var(--shadow-lg) !important;
          }

          .swal2-title {
            color: var(--text-primary) !important;
            font-size: 1.125rem !important;
            font-weight: 700 !important;
          }

          .swal2-html-container {
            color: var(--text-accent) !important;
            font-size: 0.875rem !important;
          }

          .swal2-confirm {
            background: var(--brand-gradient) !important;
            border: none !important;
            padding: 0.75rem 1.5rem !important;
            font-weight: 600 !important;
            border-radius: 0.75rem !important;
            color: var(--bg-primary) !important;
          }

          .swal2-cancel {
            background: rgba(30,41,59,.9) !important;
            border: 1px solid var(--border-default) !important;
            padding: 0.75rem 1.5rem !important;
            border-radius: 0.75rem !important;
            color: var(--text-primary) !important;
          }

          .swal2-icon {
            border-color: var(--border-subtle) !important;
          }
        </style>
        """

        self._login_css = """
        <style>
          :root {
            /* Design System Colors - OLED Dark Mode */
            --bg-primary: #020617;
            --bg-secondary: #0F172A;
            --text-primary: #F8FAFC;
            --text-secondary: #94A3B8;
            --text-accent: #CBD5E1;
            --border-subtle: rgba(148,163,184,.12);
            --border-default: rgba(148,163,184,.18);

            /* Brand */
            --brand-gradient: linear-gradient(135deg, #22D3EE 0%, #60A5FA 100%);
            --brand-glow: rgba(34,211,238,.4);

            /* Timing */
            --duration-normal: 200ms;
            --duration-slow: 300ms;
            --ease-out: cubic-bezier(0, 0, 0.2, 1);
          }

          *, *::before, *::after {
            box-sizing: border-box;
          }

          html {
            font-size: 16px;
            -webkit-font-smoothing: antialiased;
            -moz-osx-font-smoothing: grayscale;
          }

          body {
            margin: 0;
            font-family: 'Fira Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            font-size: 0.875rem;
            line-height: 1.6;
            color: var(--text-primary);
            background:
              radial-gradient(ellipse 140% 80% at 15% 0%, rgba(34,211,238,.08) 0%, transparent 50%),
              radial-gradient(ellipse 140% 80% at 85% 100%, rgba(96,165,250,.08) 0%, transparent 50%),
              var(--bg-primary);
            min-height: 100vh;
          }

          :focus-visible {
            outline: 2px solid #22D3EE;
            outline-offset: 2px;
            border-radius: 4px;
          }

          .wrap {
            max-width: 1000px;
            margin: 0 auto;
            padding: 3.75rem 1.25rem;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
          }

          .shell {
            width: 100%;
            display: grid;
            grid-template-columns: 1.1fr 0.9fr;
            gap: 1.5rem;
            align-items: center;
          }

          .hero {
            padding: 2rem;
            border-radius: 1.25rem;
            background: rgba(15,23,42,.5);
            border: 1px solid var(--border-subtle);
            backdrop-filter: blur(12px);
            animation: fadeIn 0.6s var(--ease-out);
            transition: transform var(--duration-slow) var(--ease-out);
          }

          .hero:hover {
            transform: translateY(-2px);
          }

          .hero-title {
            font-size: 1.5rem;
            font-weight: 700;
            letter-spacing: 0.02em;
            margin: 0 0 0.75rem 0;
            background: var(--brand-gradient);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
          }

          .hero-sub {
            color: var(--text-secondary);
            font-size: 0.875rem;
            line-height: 1.7;
          }

          .card {
            padding: 2rem;
            border-radius: 1.25rem;
            background: rgba(15,23,42,.6);
            border: 1px solid var(--border-subtle);
            box-shadow: 0 20px 60px rgba(0,0,0,.4);
            backdrop-filter: blur(12px);
            animation: slideInUp 0.6s var(--ease-out);
            transition: all var(--duration-slow) var(--ease-out);
          }

          .card:hover {
            transform: translateY(-2px);
            box-shadow: 0 24px 70px rgba(0,0,0,.5);
            border-color: rgba(96,165,250,.15);
          }

          h1 {
            margin: 0 0 0.5rem 0;
            font-size: 1rem;
            color: var(--text-accent);
            letter-spacing: 0.02em;
            font-weight: 700;
          }

          label {
            display: block;
            font-size: 0.8125rem;
            color: var(--text-secondary);
            margin-top: 1rem;
            font-weight: 500;
            letter-spacing: 0.01em;
          }

          input {
            width: 100%;
            padding: 0.875rem 1rem;
            border-radius: 0.75rem;
            border: 1px solid var(--border-default);
            background: rgba(2,6,23,.6);
            color: var(--text-primary);
            outline: none;
            font-size: 0.875rem;
            font-family: inherit;
            transition: all var(--duration-normal) var(--ease-out);
          }

          input:hover {
            border-color: rgba(148,163,184,.25);
          }

          input:focus {
            border-color: rgba(96,165,250,.6);
            box-shadow: 0 0 0 3px rgba(59,130,246,.15);
            transform: translateY(-1px);
          }

          button {
            margin-top: 1.25rem;
            padding: 0.875rem 1.25rem;
            border-radius: 0.75rem;
            border: 1px solid var(--border-default);
            background: var(--brand-gradient);
            color: var(--bg-primary);
            cursor: pointer;
            width: 100%;
            font-weight: 700;
            font-size: 0.9375rem;
            font-family: inherit;
            transition: all var(--duration-normal) var(--ease-out);
            box-shadow: 0 4px 16px var(--brand-glow);
          }

          button:hover {
            transform: translateY(-2px);
            box-shadow: 0 8px 24px var(--brand-glow);
          }

          button:active {
            transform: translateY(0);
            transition: transform 100ms var(--ease-out);
          }

          button:focus-visible {
            outline: 2px solid #22D3EE;
            outline-offset: 2px;
          }

          .msg {
            padding: 0.875rem 1.125rem;
            border-radius: 0.75rem;
            background: rgba(2,6,23,.6);
            border: 1px solid var(--border-default);
            margin: 1rem 0;
            animation: fadeIn 0.4s var(--ease-out);
          }

          .muted {
            color: var(--text-secondary);
            font-size: 0.8125rem;
            margin-top: 1rem;
            text-align: center;
          }

          @keyframes fadeIn {
            from { opacity: 0; }
            to { opacity: 1; }
          }

          @keyframes slideInUp {
            from {
              opacity: 0;
              transform: translateY(16px);
            }
            to {
              opacity: 1;
              transform: translateY(0);
            }
          }

          @media (prefers-reduced-motion: reduce) {
            *,
            *::before,
            *::after {
              animation-duration: 0.01ms !important;
              animation-iteration-count: 1 !important;
              transition-duration: 0.01ms !important;
            }
            .hero:hover, .card:hover, button:hover {
              transform: none !important;
            }
          }

          @media (max-width: 860px) {
            .shell {
              grid-template-columns: 1fr;
            }
            .hero {
              display: none;
            }
            .wrap {
              padding: 2.5rem 1.25rem;
            }
          }

          /* SweetAlert2 Theme */
          .swal2-popup {
            background: rgba(15,23,42,.98) !important;
            border: 1px solid var(--border-subtle) !important;
            border-radius: 1rem !important;
            backdrop-filter: blur(12px) !important;
            box-shadow: 0 20px 60px rgba(0,0,0,.5) !important;
          }

          .swal2-title {
            color: var(--text-primary) !important;
            font-size: 1.125rem !important;
            font-weight: 700 !important;
          }

          .swal2-html-container {
            color: var(--text-accent) !important;
            font-size: 0.875rem !important;
          }

          .swal2-confirm {
            background: var(--brand-gradient) !important;
            border: none !important;
            padding: 0.75rem 1.5rem !important;
            font-weight: 600 !important;
            border-radius: 0.75rem !important;
            color: var(--bg-primary) !important;
          }
        </style>
        """

        # SweetAlert2 CDN
        self._sweetalert_script = '<script src="https://cdn.jsdelivr.net/npm/sweetalert2@11"></script>'

    def render_page(self, title: str, body_html: str, message: str = None) -> HTMLResponse:
        """Render admin page with layout.

        Args:
            title: Page title
            body_html: Page body HTML
            message: Optional message to display

        Returns:
            HTMLResponse with complete page HTML
        """
        msg_html = f'<div class="msg">{message}</div>' if message else ""
        html = f"""
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        {self._google_fonts}
        {self._admin_css}
        {self._sweetalert_script}
        <title>{title}</title>
        </head>
        <body>
          <div class="app">
            <aside class="sidebar">
              <div class="brand">
                <div class="logo"></div>
                <div>
                  <div class="brand-title">Captcha Admin</div>
                  <div class="brand-sub">白名单 · 会员 · 监控</div>
                </div>
              </div>
              <div class="nav">
                <a href="/admin">首页</a>
                <a href="/admin/whitelist">白名单</a>
                <a href="/admin/members">会员</a>
                <a href="/admin/renewals">续费通知</a>
                <a href="/admin/recognize">验证码识别</a>
                <a href="/admin/password">改密</a>
              </div>
            </aside>
            <main class="main">
              <div class="header">
                <div class="title">{title}</div>
                <form class="inline" method="post" action="/admin/logout"><button type="submit">退出</button></form>
              </div>
              <div class="content">
                {msg_html}
                {body_html}
              </div>
            </main>
          </div>
        </body></html>
        """
        return HTMLResponse(html)

    def login_page(self, message: str = None) -> HTMLResponse:
        """Render login page.

        Args:
            message: Optional error message to display

        Returns:
            HTMLResponse with login page HTML
        """
        msg_html = f'<div class="msg">{message}</div>' if message else ""
        html = f"""
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        {self._google_fonts}
        {self._login_css}
        {self._sweetalert_script}
        <title>管理员登录</title>
        </head>
        <body><div class="wrap">
          <div class="shell">
            <div class="hero">
              <div style="display:flex;align-items:center;gap:10px;margin-bottom:12px;">
                <div style="width:38px;height:38px;border-radius:12px;background:linear-gradient(135deg,#22d3ee,#60a5fa);"></div>
                <div>
                  <div style="font-weight:800;">Captcha Admin</div>
                  <div style="font-size:12px;color:#94a3b8;margin-top:2px;">后台管理 · 白名单 · 会员 · 续费</div>
                </div>
              </div>
              <div class="hero-title">欢迎回来</div>
              <div class="hero-sub">登录后可维护 IP/QQ 白名单、会员等级、调用次数与月卡续费天数，并在续费通知中审批延长月卡。</div>
            </div>
            <div class="card">
              <h1>管理员登录</h1>
              {msg_html}
              <form method="post" action="/admin/login" id="loginForm">
                <label>账号</label>
                <input name="username" value="admin" autocomplete="username" />
                <label>密码</label>
                <input name="password" type="password" autocomplete="current-password" />
                <button type="submit">登录</button>
              </form>
              <div class="muted">默认账号：admin 默认密码：admin（首次登录后强制改密）</div>
            </div>
          </div>
        </div></body></html>
        """
        return HTMLResponse(html)
