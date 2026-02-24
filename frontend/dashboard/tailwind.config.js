/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        primary: '#00ff88',
        danger: '#ff3366',
        warning: '#ffaa00',
        surface: '#1a1a1a',
        border: '#2a2a2a',
      },
      fontFamily: {
        mono: ['JetBrains Mono', 'monospace'],
        display: ['Space Mono', 'monospace'],
      },
    },
  },
  plugins: [],
}