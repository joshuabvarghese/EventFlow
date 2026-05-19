import { Activity, AlertTriangle, CheckCircle2, Database, Zap } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  Area, AreaChart, Bar, BarChart,
  CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis
} from 'recharts';
import './App.css';

// -- Types --------------------------------------------------------------------

interface EventStats {
  totalReceived:   number;
  totalProcessed:  number;
  totalFailed:     number;
  totalDuplicated: number;
  successRate:     number;
  eventsByType:    Record<string, number>;
  uptimeSeconds:   number;
}

interface TimeSeriesData {
  timestamp: string;
  events:    number;
}

interface LogLine {
  id:     number;
  time:   string;
  type:   string;
  userId: string;
  status: string;
}

// -- Static config -------------------------------------------------------------

const EVENT_TYPES = [
  'analytics.page_view',
  'user.login',
  'transaction.created',
  'user.signup',
  'system.error',
  'fraud.detected',
];

const INITIAL_COUNTS: Record<string, number> = {
  'analytics.page_view':  124_000,
  'user.login':            62_400,
  'transaction.created':   28_100,
  'user.signup':           14_900,
  'system.error':           4_200,
  'fraud.detected':           890,
};

const INFRA = [
  { name: 'Apache Kafka 3.6',  role: 'Message Broker'  },
  { name: 'Redis 7.2',         role: 'Deduplication'   },
  { name: 'PostgreSQL 16',     role: 'Event Store'      },
  { name: 'Prometheus',        role: 'Metrics'          },
  { name: 'Stream Processor',  role: 'Enrichment'       },
];

const LATENCY_PERCENTILES = [
  { label: 'P50', ms: 12  },
  { label: 'P90', ms: 38  },
  { label: 'P95', ms: 62  },
  { label: 'P99', ms: 94  },
];

// -- Simulation helpers --------------------------------------------------------

function jitter(base: number, pct = 0.06): number {
  return Math.round(base * (1 + (Math.random() - 0.5) * pct));
}

function makeInitialStats(): EventStats {
  return {
    totalReceived:   847_293,
    totalProcessed:  845_801,
    totalFailed:         312,
    totalDuplicated:     180,
    successRate:       99.72,
    eventsByType:    { ...INITIAL_COUNTS },
    uptimeSeconds:   51_720,
  };
}

let logIdSeq = 0;
function makeLogLine(eventsByType: Record<string, number>): LogLine {
  const types = Object.keys(eventsByType);
  const pool  = types.length ? types : EVENT_TYPES;
  // Weight toward high-volume types
  const weights = pool.map(t => eventsByType[t] ?? 1);
  const total   = weights.reduce((a, b) => a + b, 0);
  let r = Math.random() * total;
  let picked = pool[0];
  for (let i = 0; i < pool.length; i++) {
    r -= weights[i];
    if (r <= 0) { picked = pool[i]; break; }
  }
  const now = new Date();
  return {
    id:     ++logIdSeq,
    time:   now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
    type:   picked,
    userId: 'u_' + Math.random().toString(36).slice(2, 9),
    status: Math.random() > 0.02 ? 'OK' : 'ERR',
  };
}

function seedSeries(): TimeSeriesData[] {
  const now = Date.now();
  return Array.from({ length: 20 }, (_, i) => {
    const d = new Date(now - (20 - i) * 2500);
    return {
      timestamp: d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
      events:    jitter(5_200, 0.25),
    };
  });
}

// -- Custom tooltip ------------------------------------------------------------

const CustomTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={{
      background: '#1c1a17',
      border: '1px solid rgba(255,255,255,0.08)',
      padding: '8px 12px',
      fontFamily: 'Geist Mono, monospace',
      fontSize: 11,
    }}>
      <div style={{ color: 'rgba(255,255,255,0.35)', marginBottom: 2 }}>{label}</div>
      <div style={{ color: '#f07030', fontWeight: 600 }}>{payload[0].value.toLocaleString()}</div>
    </div>
  );
};

// -- App -----------------------------------------------------------------------

export default function App() {
  const [stats, setStats]           = useState<EventStats>(makeInitialStats);
  const [series, setSeries]         = useState<TimeSeriesData[]>(seedSeries);
  const [logLines, setLogLines]     = useState<LogLine[]>([]);
  const [activeTab, setActiveTab]   = useState('Overview');
  const [uptimeSec, setUptimeSec]   = useState(51_720);
  const logRef                      = useRef<HTMLDivElement>(null);

  // Seed initial log lines
  useEffect(() => {
    const seed: LogLine[] = [];
    for (let i = 0; i < 18; i++) seed.push(makeLogLine(INITIAL_COUNTS));
    setLogLines(seed);
  }, []);

  // Main simulation tick — every 2.5 s
  useEffect(() => {
    const interval = setInterval(() => {
      const tickEvents  = jitter(5_200, 0.30);
      const tickFailed  = Math.random() > 0.85 ? Math.floor(Math.random() * 4) : 0;

      setStats(prev => {
        const received  = prev.totalReceived  + tickEvents + tickFailed;
        const processed = prev.totalProcessed + tickEvents;
        const failed    = prev.totalFailed    + tickFailed;
        const rate      = Math.round(((processed / received) * 10000)) / 100;

        // Tick each event type count
        const byType = { ...prev.eventsByType };
        EVENT_TYPES.forEach(t => {
          const base = INITIAL_COUNTS[t] ?? 100;
          byType[t]  = (byType[t] ?? base) + jitter(Math.round(base * 0.004), 0.5);
        });

        return { ...prev, totalReceived: received, totalProcessed: processed, totalFailed: failed, successRate: rate, eventsByType: byType };
      });

      setSeries(prev => {
        const point: TimeSeriesData = {
          timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
          events:    tickEvents,
        };
        return [...prev, point].slice(-24);
      });

      // Add 1-3 log lines per tick
      const count = Math.floor(Math.random() * 3) + 1;
      setLogLines(prev => {
        const next = [...prev];
        for (let i = 0; i < count; i++) next.unshift(makeLogLine(INITIAL_COUNTS));
        return next.slice(0, 40);
      });
    }, 2500);

    return () => clearInterval(interval);
  }, []);

  // Uptime counter — every second
  useEffect(() => {
    const t = setInterval(() => setUptimeSec(s => s + 1), 1000);
    return () => clearInterval(t);
  }, []);

  // Keep log scrolled to top
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = 0;
  }, [logLines.length]);

  // Derived values
  const totalByType = Object.values(stats.eventsByType).reduce((a, b) => a + b, 0) || 1;
  const distData    = Object.entries(stats.eventsByType)
    .map(([name, value]) => ({ name, short: name.split('.').pop()!.replace(/_/g, ' '), value, pct: Math.round((value / totalByType) * 100) }))
    .sort((a, b) => b.value - a.value);

  const barData = distData.slice(0, 6).map(d => ({ name: d.short, value: d.value }));
  const maxPct  = LATENCY_PERCENTILES[LATENCY_PERCENTILES.length - 1].ms;
  const uptimeH = Math.floor(uptimeSec / 3600);
  const uptimeM = Math.floor((uptimeSec % 3600) / 60);
  const lastTick = series[series.length - 1]?.events ?? 0;
  const tabs = ['Overview', 'Events', 'Latency', 'Infrastructure'];

  return (
    <div className="app">

      {/* -- TICKER ------------------------------------------------------- */}
      <div className="ticker-bar">
        <div className="ticker-left">
          <span><span className="ping-dot" />STREAM CONNECTED</span>
          <span className="t-sep" />
          <span>SIMULATED · {new Date().toLocaleTimeString()}</span>
        </div>
        <div className="ticker-right">
          <span className="t-item">RECEIVED <strong>{stats.totalReceived.toLocaleString()}</strong></span>
          <span className="t-sep" />
          <span className="t-item">PROCESSED <strong>{stats.totalProcessed.toLocaleString()}</strong></span>
          <span className="t-sep" />
          <span className="t-item">FAILED <strong>{stats.totalFailed.toLocaleString()}</strong></span>
          <span className="t-sep" />
          <span className="t-item">SUCCESS <strong>{stats.successRate.toFixed(2)}%</strong></span>
        </div>
      </div>

      {/* -- HEADER ------------------------------------------------------- */}
      <header className="header">
        <div className="header-inner">

          <div className="brand">
            <div className="brand-mark">
              <Activity size={17} strokeWidth={2.2} />
            </div>
            <div>
              <div className="brand-name">EventFlow</div>
              <div className="brand-sub">Stream Processing Platform</div>
            </div>
          </div>

          <nav className="nav">
            {tabs.map((t, i) => (
              <div key={t} className={`nav-item${activeTab === t ? ' active' : ''}`} onClick={() => setActiveTab(t)}>
                <span className="nav-num">0{i + 1}</span>{t}
              </div>
            ))}
          </nav>

          <div className="header-kpis">
            <div className="kpi-cell">
              <div className="kpi-lbl">Throughput</div>
              <div className="kpi-val amber">
                {lastTick.toLocaleString()}
                <span style={{ fontSize: 10, marginLeft: 3, color: 'var(--ink-4)' }}>/tick</span>
              </div>
            </div>
            <div className="kpi-cell">
              <div className="kpi-lbl">Success Rate</div>
              <div className="kpi-val green">{stats.successRate.toFixed(2)}%</div>
            </div>
            <div className="kpi-cell">
              <div className="kpi-lbl">Uptime</div>
              <div className="kpi-val">{uptimeH}h {uptimeM}m</div>
            </div>
          </div>

        </div>
      </header>

      {/* -- MAIN --------------------------------------------------------- */}
      <main className="main">

        {/* KPI ROW */}
        <div className="section-head">
          <span className="section-head-title">Key Metrics</span>
          <div className="section-rule" />
        </div>

        <div className="row-3">
          <div className="metric-panel">
            <div className="metric-panel-top">
              <span className="metric-panel-label">Events Received</span>
              <div className="metric-icon amber"><Database size={14} /></div>
            </div>
            <div className="metric-num">{stats.totalReceived.toLocaleString()}</div>
            <div className="metric-foot">
              <span className="badge up">+12.5%</span>vs yesterday
            </div>
          </div>

          <div className="metric-panel">
            <div className="metric-panel-top">
              <span className="metric-panel-label">Events Processed</span>
              <div className="metric-icon green"><Zap size={14} /></div>
            </div>
            <div className="metric-num">{stats.totalProcessed.toLocaleString()}</div>
            <div className="metric-foot">
              <CheckCircle2 size={12} style={{ color: 'var(--green)', flexShrink: 0 }} />
              Exactly-once semantics
            </div>
          </div>

          <div className="metric-panel">
            <div className="metric-panel-top">
              <span className="metric-panel-label">Failed (DLQ)</span>
              <div className="metric-icon red"><AlertTriangle size={14} /></div>
            </div>
            <div className="metric-num">{stats.totalFailed.toLocaleString()}</div>
            <div className="metric-foot">
              <span className="badge neu">
                {stats.totalReceived > 0
                  ? ((stats.totalFailed / stats.totalReceived) * 100).toFixed(2)
                  : '0.00'}%
              </span>
              of total volume
            </div>
          </div>
        </div>

        {/* CHARTS ROW */}
        <div className="section-head">
          <span className="section-head-title">Throughput &amp; Distribution</span>
          <div className="section-rule" />
        </div>

        <div className="row-2-1">
          <div className="panel">
            <div className="panel-head">
              <div>
                <div className="panel-title">Event Throughput</div>
                <div className="panel-sub">Events per interval — simulated stream</div>
              </div>
              <span className="panel-tag live">● Live Sim</span>
            </div>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={series} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
                <defs>
                  <linearGradient id="g1" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%"   stopColor="#d4590a" stopOpacity={0.18} />
                    <stop offset="100%" stopColor="#d4590a" stopOpacity={0}    />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="2 4" stroke="rgba(28,26,23,0.08)" />
                <XAxis dataKey="timestamp" tick={{ fill: '#9b9485', fontSize: 9.5, fontFamily: 'Geist Mono, monospace' }} tickLine={false} axisLine={false} interval={5} />
                <YAxis tick={{ fill: '#9b9485', fontSize: 9.5, fontFamily: 'Geist Mono, monospace' }} tickLine={false} axisLine={false} />
                <Tooltip content={<CustomTooltip />} />
                <Area type="monotone" dataKey="events" stroke="#d4590a" strokeWidth={2} fill="url(#g1)" dot={false} activeDot={{ r: 3, fill: '#d4590a', strokeWidth: 0 }} />
              </AreaChart>
            </ResponsiveContainer>
          </div>

          <div className="panel">
            <div className="panel-head">
              <div>
                <div className="panel-title">By Type</div>
                <div className="panel-sub">Volume distribution</div>
              </div>
            </div>
            <div className="dist-list">
              {distData.slice(0, 6).map(d => (
                <div className="dist-row" key={d.name}>
                  <span className="dist-name">{d.name}</span>
                  <div className="dist-bar-wrap">
                    <div className="dist-bar" style={{ width: `${d.pct}%` }} />
                  </div>
                  <span className="dist-count">{d.value.toLocaleString()}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* BOTTOM ROW */}
        <div className="section-head">
          <span className="section-head-title">Latency &amp; Infrastructure</span>
          <div className="section-rule" />
        </div>

        <div className="row-1-1">
          <div className="panel">
            <div className="panel-head">
              <div>
                <div className="panel-title">Latency Percentiles</div>
                <div className="panel-sub">Ingestion pipeline response times</div>
              </div>
              <span className="panel-tag ok">Sub-100ms</span>
            </div>
            <ResponsiveContainer width="100%" height={130}>
              <BarChart data={barData} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
                <CartesianGrid strokeDasharray="2 4" stroke="rgba(28,26,23,0.08)" vertical={false} />
                <XAxis dataKey="name" tick={{ fill: '#9b9485', fontSize: 9, fontFamily: 'Geist Mono, monospace' }} tickLine={false} axisLine={false} />
                <YAxis tick={{ fill: '#9b9485', fontSize: 9, fontFamily: 'Geist Mono, monospace' }} tickLine={false} axisLine={false} />
                <Tooltip content={<CustomTooltip />} />
                <Bar dataKey="value" fill="#d4590a" fillOpacity={0.65} radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
            <div className="lat-rows">
              {LATENCY_PERCENTILES.map(p => (
                <div className="lat-row" key={p.label}>
                  <span className="lat-lbl">{p.label}</span>
                  <div className="lat-track"><div className="lat-fill" style={{ width: `${(p.ms / maxPct) * 100}%` }} /></div>
                  <span className="lat-val">{p.ms}ms</span>
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <div className="panel-head">
              <div>
                <div className="panel-title">Infrastructure</div>
                <div className="panel-sub">Component status</div>
              </div>
              <span className="panel-tag ok">All Nominal</span>
            </div>
            <table className="infra-table">
              <thead>
                <tr>
                  <th>Component</th>
                  <th>Role</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {INFRA.map(row => (
                  <tr key={row.name}>
                    <td style={{ fontFamily: 'var(--f-mono)', fontSize: 12 }}>{row.name}</td>
                    <td style={{ color: 'var(--ink-4)', fontSize: 12 }}>{row.role}</td>
                    <td>
                      <span className="status-chip up">
                        <span className="status-chip-dot" />UP
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* EVENT LOG */}
        <div className="section-head">
          <span className="section-head-title">Live Event Stream</span>
          <div className="section-rule" />
        </div>

        <div className="row-full">
          <div className="panel" style={{ padding: 0 }}>
            <div className="panel-head" style={{ padding: '14px 20px 12px', marginBottom: 0 }}>
              <div>
                <div className="panel-title">Ingestion Log</div>
                <div className="panel-sub">Simulated event activity — newest first</div>
              </div>
              <span className="panel-tag live">● Streaming</span>
            </div>
            <div className="event-log" ref={logRef}>
              {logLines.map(l => (
                <div className="log-line" key={l.id}>
                  <span className="log-time">{l.time}</span>
                  <span className="log-type">{l.type}</span>
                  <span className="log-uid">{l.userId}</span>
                  <span className={l.status === 'OK' ? 'log-ok' : 'log-err'}>{l.status}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

      </main>

      {/* -- FOOTER ------------------------------------------------------- */}
      <footer className="footer">
        <div className="footer-inner">
          <span>© 2026 EventFlow Platform — Demo Mode</span>
          <div className="footer-chips">
            <span>Apache Kafka 3.6</span>
            <span className="footer-sep" />
            <span>Java 21</span>
            <span className="footer-sep" />
            <span>Spring Boot 3.2.5</span>
            <span className="footer-sep" />
            <span>v1.0.0-SNAPSHOT</span>
          </div>
        </div>
      </footer>

    </div>
  );
}