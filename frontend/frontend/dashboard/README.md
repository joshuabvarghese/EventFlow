# EventFlow Dashboard

Professional real-time monitoring dashboard for the EventFlow distributed stream processing platform.

## Tech Stack

- **React 18** + TypeScript
- **Recharts** — charting library
- **Lucide React** — icons
- **Vite** — build tool
- **IBM Plex Mono** + **Outfit** — fonts (loaded via Google Fonts)

## Getting Started

```bash
# Install dependencies
npm install

# Start dev server (opens at http://localhost:3000)
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

## Project Structure

```
src/
├── App.tsx       # Main dashboard component (all logic + layout)
├── App.css       # All styles — design tokens, components, responsive
├── index.css     # Base reset
├── main.tsx      # React entry point
└── vite-env.d.ts # Vite type declarations
```

## Design System

The dashboard uses a cohesive dark theme defined in CSS custom properties at the top of `App.css`:

| Token | Value | Usage |
|---|---|---|
| `--bg-base` | `#07090f` | Page background |
| `--bg-surface` | `#0d1117` | Cards, header |
| `--accent-blue` | `#3b82f6` | Primary accent |
| `--success` | `#34d399` | Healthy states |
| `--danger` | `#f87171` | Errors / failed events |
| `--font-display` | Outfit | Labels, UI text |
| `--font-mono` | IBM Plex Mono | Numbers, code, timestamps |

## Backend Integration

The dashboard currently runs with simulated live data. To connect to the real EventFlow backend:

1. The Vite dev proxy is already configured to forward `/api` requests to `http://localhost:8081`
2. Replace the `setInterval` simulation in `App.tsx` with real API calls or WebSocket connection to your event-ingestion-service
