/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
      },
      colors: {
        // Night-sky surfaces, from page background (950) to raised controls (600)
        ink: {
          950: '#0b0914',
          900: '#110e1f',
          850: '#16122a',
          800: '#1d1836',
          700: '#2a2448',
          600: '#3b3460',
          500: '#5b5480',
          400: '#8b85a8',
          300: '#b4afcb',
          200: '#d6d2e6',
          100: '#ece9f7',
        },
        // Brand: a warm coral-to-amber "marquee" gradient
        flame: { 300: '#ff9a8b', 400: '#ff7166', 500: '#ff4f62', 600: '#e63a50' },
        sun: { 300: '#ffd98a', 400: '#ffc35c', 500: '#ffad33' },
        // Supporting accents
        grape: { 400: '#a78bfa', 500: '#8b5cf6', 600: '#7c3aed' },
        mint: { 300: '#7ef0d6', 400: '#3ee0bd', 500: '#14c9a3' },
      },
      backgroundImage: {
        marquee: 'linear-gradient(120deg, #ff4f62 0%, #ff8a4c 55%, #ffc35c 100%)',
        aurora: 'radial-gradient(60% 80% at 15% 0%, rgba(139,92,246,0.35) 0%, transparent 60%), radial-gradient(50% 70% at 90% 10%, rgba(255,79,98,0.28) 0%, transparent 60%)',
      },
      boxShadow: {
        glow: '0 10px 40px -10px rgba(255, 79, 98, 0.55)',
        card: '0 1px 0 0 rgba(255,255,255,0.04) inset, 0 20px 40px -24px rgba(0,0,0,0.7)',
      },
      keyframes: {
        'fade-up': { '0%': { opacity: 0, transform: 'translateY(8px)' }, '100%': { opacity: 1, transform: 'none' } },
        pulseRing: { '0%': { boxShadow: '0 0 0 0 rgba(62,224,189,0.6)' }, '100%': { boxShadow: '0 0 0 8px rgba(62,224,189,0)' } },
      },
      animation: {
        'fade-up': 'fade-up 300ms ease-out both',
        'pulse-ring': 'pulseRing 1.6s ease-out infinite',
      },
    },
  },
  plugins: [],
};
