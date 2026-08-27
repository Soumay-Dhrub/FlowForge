import { CheckCircle2, GitBranch, ShieldCheck, Zap } from "lucide-react";

/** Nodes of the illustrated graph, positioned on a 320×420 viewBox. */
const NODES = [
  { id: "start", label: "Request raised", type: "Start", x: 40, y: 34, delay: 0 },
  { id: "condition", label: "Amount > £500?", type: "Condition", x: 40, y: 122, delay: 0.9 },
  { id: "manager", label: "Manager review", type: "Approval", x: 12, y: 216, delay: 1.8 },
  { id: "finance", label: "Finance sign-off", type: "Approval", x: 176, y: 216, delay: 1.8 },
  { id: "done", label: "Approved", type: "End", x: 40, y: 322, delay: 3.0 },
] as const;

export function AuthShowcase() {
  return (
    <div
      aria-hidden
      className="relative hidden overflow-hidden bg-gradient-to-br from-primary-800 via-primary-700 to-primary-900 lg:flex lg:w-[46%] lg:flex-col lg:justify-between xl:w-1/2"
    >
      {/* Depth behind the diagram: two soft lights and a fine grid, so the panel reads as a surface
          rather than a flat fill. */}
      <div className="pointer-events-none absolute -left-24 top-10 h-72 w-72 rounded-full bg-primary-400/20 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-24 -right-16 h-80 w-80 rounded-full bg-primary-300/15 blur-3xl" />
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.07]"
        style={{
          backgroundImage:
            "linear-gradient(to right, white 1px, transparent 1px), linear-gradient(to bottom, white 1px, transparent 1px)",
          backgroundSize: "44px 44px",
        }}
      />

      <div className="relative px-10 pt-12 xl:px-14">
        <p className="inline-flex items-center gap-2 rounded-full bg-white/10 px-3 py-1 text-xs font-medium text-primary-100 ring-1 ring-inset ring-white/15">
          <Zap className="h-3 w-3" />
          Workflow orchestration
        </p>
        <h2 className="mt-5 max-w-md text-3xl font-semibold leading-tight text-white">
          Design a process once. Let it run itself.
        </h2>
        <p className="mt-3 max-w-md text-sm leading-relaxed text-primary-100/80">
          Draw approvals as a graph, publish it as an immutable version, and every request that follows
          takes exactly the path you drew — with a full audit trail of who decided what.
        </p>
      </div>

      <div className="relative flex flex-1 items-center justify-center px-10 py-8">
        <Pipeline />
      </div>

      <div className="relative border-t border-white/10 px-10 py-6 xl:px-14">
        <ul className="grid grid-cols-3 gap-4 text-primary-100/90">
          <Feature icon={GitBranch} label="Parallel branches" detail="and join gates" />
          <Feature icon={ShieldCheck} label="Immutable versions" detail="running work is safe" />
          <Feature icon={CheckCircle2} label="Full audit trail" detail="append-only" />
        </ul>
      </div>
    </div>
  );
}

function Feature({
  icon: Icon,
  label,
  detail,
}: {
  icon: typeof GitBranch;
  label: string;
  detail: string;
}) {
  return (
    <li>
      <Icon className="h-4 w-4 text-primary-200" />
      <p className="mt-2 text-xs font-medium text-white">{label}</p>
      <p className="text-xs text-primary-200/70">{detail}</p>
    </li>
  );
}

function Pipeline() {
  return (
    <div className="relative w-full max-w-[320px]">
      <svg
        viewBox="0 0 320 420"
        className="h-auto w-full overflow-visible"
        role="presentation"
        focusable="false"
      >
        <defs>
          <linearGradient id="wire" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgb(199 210 254)" stopOpacity="0.9" />
            <stop offset="100%" stopColor="rgb(165 180 252)" stopOpacity="0.35" />
          </linearGradient>
        </defs>

        {/* Start → Condition */}
        <Wire d="M 100 78 L 100 118" delay={0.2} />
        {/* Condition fans out to both approvals */}
        <Wire d="M 100 166 L 100 186 Q 100 200 84 200 L 76 200 Q 62 200 62 214" delay={0.9} />
        <Wire d="M 100 166 L 100 186 Q 100 200 116 200 L 224 200 Q 240 200 240 214" delay={0.9} />
        {/* Both rejoin into the end node */}
        <Wire d="M 62 262 L 62 292 Q 62 306 78 306 L 100 306 L 100 318" delay={2.1} />
        <Wire d="M 240 262 L 240 292 Q 240 306 224 306 L 100 306" delay={2.1} />
      </svg>

      {/* The node cards sit above the wires, positioned to the same coordinate space. */}
      <div className="pointer-events-none absolute inset-0">
        {NODES.map((node) => (
          <Node key={node.id} {...node} />
        ))}
      </div>
    </div>
  );
}

/** One edge. The dash pattern travels, which is what makes it read as flow rather than as a line. */
function Wire({ d, delay }: { d: string; delay: number }) {
  return (
    <>
      {/* A static base so the wire is visible between pulses. */}
      <path d={d} fill="none" stroke="url(#wire)" strokeWidth="1.5" strokeLinecap="round" />
      <path
        d={d}
        fill="none"
        stroke="rgb(224 231 255)"
        strokeWidth="2"
        strokeLinecap="round"
        strokeDasharray="10 150"
        className="animate-flow"
        style={{ animationDelay: `${delay}s` }}
      />
    </>
  );
}

function Node({
  label,
  type,
  x,
  y,
  delay,
}: {
  label: string;
  type: string;
  x: number;
  y: number;
  delay: number;
}) {
  return (
    <div
      className="absolute w-[45%] animate-node-in rounded-lg border border-white/15 bg-white/10 px-3 py-2 shadow-lg backdrop-blur-sm"
      style={{
        left: `${(x / 320) * 100}%`,
        top: `${(y / 420) * 100}%`,
        animationDelay: `${delay}s`,
      }}
    >
      <p className="text-[0.625rem] font-medium uppercase tracking-wide text-primary-200/80">{type}</p>
      <p className="mt-0.5 truncate text-xs font-medium text-white">{label}</p>
    </div>
  );
}

export default AuthShowcase;
