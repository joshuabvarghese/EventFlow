/**
 * simulator.ts
 *
 * Generates realistic fake EventFlow data when the backend is unreachable.
 * Mimics actual traffic patterns: page_view dominates, spikes at intervals,
 * occasional failures, realistic latency drift.
 *
 * Usage:
 *   import { createSimulator } from './simulator';
 *   const sim = createSimulator();
 *   sim.start(stats => setStats(stats));
 *   sim.stop();  // on cleanup
 */

export interface SimStats {
  totalReceived:   number;
  totalProcessed:  number;
  totalFailed:     number;
  totalDuplicated: number;
  successRate:     number;
  eventsByType:    Record<string, number>;
  uptimeSeconds:   number;
}

// Realistic traffic weights — page_view dominates like real analytics pipelines
const EVENT_WEIGHTS: [string, number][] = [
  ['analytics.page_view',      42],
  ['user.login',               18],
  ['transaction.created',      12],
  ['analytics.button.click',    9],
  ['user.signup',               7],
  ['transaction.completed',     6],
  ['system.error',              3],
  ['fraud.detected',            2],
  ['security.breach.attempted', 1],
];

const TOTAL_WEIGHT = EVENT_WEIGHTS.reduce((s, [, w]) => s + w, 0);

function weightedPick(): string {
  let r = Math.random() * TOTAL_WEIGHT;
  for (const [type, w] of EVENT_WEIGHTS) {
    r -= w;
    if (r <= 0) return type;
  }
  return EVENT_WEIGHTS[0][0];
}

// Smooth noise — makes throughput feel like real bursty traffic
function smoothNoise(base: number, amplitude: number, t: number): number {
  return base
    + amplitude * Math.sin(t * 0.7)
    + (amplitude * 0.4) * Math.sin(t * 1.9 + 1.2)
    + (amplitude * 0.2) * Math.sin(t * 4.1 + 0.7)
    + (Math.random() - 0.5) * amplitude * 0.3;
}

export function createSimulator() {
  let handle: ReturnType<typeof setInterval> | null = null;

  // Persistent state — accumulates across ticks
  const state = {
    totalReceived:   0,
    totalProcessed:  0,
    totalFailed:     0,
    totalDuplicated: 0,
    eventsByType:    Object.fromEntries(EVENT_WEIGHTS.map(([t]) => [t, 0])),
    startTime:       Date.now(),
    tick:            0,
  };

  function tick(): SimStats {
    state.tick++;
    const t = state.tick * 0.4;

    // Traffic volume: base 5 200/tick, spikes up to ~9 000 every ~30 ticks
    const spike = state.tick % 28 < 4 ? 3200 : 0;
    const inbound = Math.max(
      0,
      Math.round(smoothNoise(5200, 900, t) + spike)
    );

    // Realistic failure/dupe rates
    const failRate  = 0.003 + Math.random() * 0.002;  // 0.3–0.5%
    const dupeRate  = 0.008 + Math.random() * 0.004;  // 0.8–1.2%
    const failed    = Math.round(inbound * failRate);
    const duped     = Math.round(inbound * dupeRate);
    const processed = inbound - failed - duped;

    state.totalReceived   += inbound;
    state.totalProcessed  += processed;
    state.totalFailed     += failed;
    state.totalDuplicated += duped;

    // Distribute inbound events across types by weight
    for (let i = 0; i < inbound; i++) {
      const type = weightedPick();
      state.eventsByType[type] = (state.eventsByType[type] || 0) + 1;
    }

    const successRate = state.totalReceived > 0
      ? Math.round((state.totalProcessed / state.totalReceived) * 10000) / 100
      : 100;

    return {
      totalReceived:   state.totalReceived,
      totalProcessed:  state.totalProcessed,
      totalFailed:     state.totalFailed,
      totalDuplicated: state.totalDuplicated,
      successRate,
      eventsByType:    { ...state.eventsByType },
      uptimeSeconds:   Math.floor((Date.now() - state.startTime) / 1000),
    };
  }

  return {
    /** Seed an initial snapshot immediately, then tick every 2.5 s */
    start(onTick: (stats: SimStats) => void): SimStats {
      const initial = tick();
      onTick(initial);
      handle = setInterval(() => onTick(tick()), 2500);
      return initial;
    },
    stop() {
      if (handle !== null) clearInterval(handle);
      handle = null;
    },
  };
}
