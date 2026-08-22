import type { Config } from "tailwindcss";

/**
 * Design tokens for FlowForge.
 *
 * The palette is deliberately narrow. This is an internal tool people use for hours at a time, so the
 * job is legibility and calm rather than personality: one accent colour that means "interactive or
 * current", a neutral ramp that carries almost all of the interface, and semantic colours reserved for
 * state so they keep their meaning. Colour is never the only signal — status pills carry text, the
 * active nav item has a bar as well as a tint — because roughly one in twelve men cannot separate red
 * from green.
 *
 * Full ramps rather than the three shades this started with: a hover state that is a real step darker,
 * a border that is a real step lighter than its fill, and a disabled control that reads as disabled all
 * need neighbouring values to exist.
 */
const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        // Indigo rather than the default blue: it holds up against the greys without competing with
        // the semantic blue used for informational states.
        primary: {
          50: "#eef2ff",
          100: "#e0e7ff",
          200: "#c7d2fe",
          300: "#a5b4fc",
          400: "#818cf8",
          500: "#6366f1",
          600: "#4f46e5",
          700: "#4338ca",
          800: "#3730a3",
          900: "#312e81",
          950: "#1e1b4b",
        },
        // Slate, so the neutrals sit slightly cool alongside the accent instead of muddying it.
        gray: {
          50: "#f8fafc",
          100: "#f1f5f9",
          200: "#e2e8f0",
          300: "#cbd5e1",
          400: "#94a3b8",
          500: "#64748b",
          600: "#475569",
          700: "#334155",
          800: "#1e293b",
          900: "#0f172a",
          950: "#020617",
        },
        success: {
          50: "#f0fdf4",
          100: "#dcfce7",
          200: "#bbf7d0",
          600: "#16a34a",
          700: "#15803d",
          800: "#166534",
        },
        warning: {
          50: "#fffbeb",
          100: "#fef3c7",
          200: "#fde68a",
          600: "#d97706",
          700: "#b45309",
          800: "#92400e",
        },
        danger: {
          50: "#fef2f2",
          100: "#fee2e2",
          200: "#fecaca",
          600: "#dc2626",
          700: "#b91c1c",
          800: "#991b1b",
        },
        info: {
          50: "#eff6ff",
          100: "#dbeafe",
          200: "#bfdbfe",
          600: "#2563eb",
          700: "#1d4ed8",
          800: "#1e40af",
        },
      },
      fontFamily: {
        // Supplied by next/font in the root layout, which self-hosts it — no third-party request on
        // first paint, and no flash of unstyled text.
        sans: ["var(--font-inter)", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["var(--font-mono)", "ui-monospace", "SFMono-Regular", "monospace"],
      },
      fontSize: {
        // Slightly tightened tracking on the display sizes; at large sizes Inter's default spacing
        // reads loose in headings.
        "2xl": ["1.5rem", { lineHeight: "2rem", letterSpacing: "-0.014em" }],
        "3xl": ["1.875rem", { lineHeight: "2.25rem", letterSpacing: "-0.018em" }],
      },
      borderRadius: {
        // One step softer than Tailwind's defaults. Cards and inputs share a radius so a form sitting
        // inside a card does not look like it was pasted in.
        md: "0.5rem",
        lg: "0.625rem",
        xl: "0.875rem",
      },
      boxShadow: {
        // Low-contrast, tightly stacked shadows: elevation should be felt rather than seen. The
        // two-layer form gives a contact shadow plus a soft spread, which reads as depth where a
        // single large blur reads as blur.
        xs: "0 1px 2px 0 rgb(15 23 42 / 0.04)",
        sm: "0 1px 2px 0 rgb(15 23 42 / 0.06), 0 1px 3px 0 rgb(15 23 42 / 0.06)",
        md: "0 2px 4px -1px rgb(15 23 42 / 0.06), 0 4px 8px -2px rgb(15 23 42 / 0.08)",
        lg: "0 4px 6px -2px rgb(15 23 42 / 0.06), 0 12px 20px -4px rgb(15 23 42 / 0.10)",
        // For the notification dropdown and modals, which sit above everything.
        popover: "0 8px 12px -4px rgb(15 23 42 / 0.10), 0 20px 32px -8px rgb(15 23 42 / 0.16)",
      },
      keyframes: {
        "fade-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
        // Dropdowns and dialogs rise slightly as they appear, which tells the eye where they came from.
        "scale-in": {
          from: { opacity: "0", transform: "translateY(-4px) scale(0.98)" },
          to: { opacity: "1", transform: "translateY(0) scale(1)" },
        },
        shimmer: {
          "100%": { transform: "translateX(100%)" },
        },
        /*
         * A pulse travelling along an edge of the login page's pipeline diagram. Animating
         * stroke-dashoffset moves a short dash along the path, which reads as flow; the opacity ramp at
         * either end stops it appearing and vanishing abruptly at the node it is entering.
         */
        flow: {
          "0%": { strokeDashoffset: "160", opacity: "0" },
          "12%": { opacity: "1" },
          "80%": { opacity: "1" },
          "100%": { strokeDashoffset: "0", opacity: "0" },
        },
        /* A node lighting up as the pulse reaches it. */
        "node-in": {
          "0%": { opacity: "0.55", transform: "translateY(2px)" },
          "50%": { opacity: "1", transform: "translateY(0)" },
          "100%": { opacity: "0.85", transform: "translateY(0)" },
        },
      },
      animation: {
        "fade-in": "fade-in 120ms ease-out",
        "scale-in": "scale-in 120ms ease-out",
        // One shared 4.2s cycle for the diagram, so the pulses and the node highlights stay in step
        // however long the page is open. Slow on purpose: this sits behind a password field, and
        // anything quicker competes with the form for attention.
        flow: "flow 4.2s cubic-bezier(0.4, 0, 0.2, 1) infinite",
        "node-in": "node-in 4.2s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};

export default config;
