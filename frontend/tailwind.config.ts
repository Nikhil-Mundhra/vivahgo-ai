import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: "#e11d48", // Rose / Maroon Indian Wedding aesthetic matching VivahGo
          dark: "#be123c",
          light: "#ffe4e6",
        },
        gold: {
          DEFAULT: "#d97706",
          light: "#fef3c7",
          dark: "#b45309",
        },
      },
    },
  },
  plugins: [],
};
export default config;
