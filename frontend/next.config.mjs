/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Emit a self-contained server bundle in .next/standalone for the Docker runtime stage.
  output: "standalone",
  async rewrites() {
    // Resolved by the Next.js server, not the browser, so it must be reachable from wherever the
    // server runs. In Docker that is the compose service name (API_PROXY_TARGET=http://backend:8080);
    // NEXT_PUBLIC_API_URL remains the fallback for local `pnpm dev`.
    const target =
      process.env.API_PROXY_TARGET || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
    return [
      {
        source: "/api/:path*",
        destination: `${target}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
