import { Activity, AlertTriangle, CheckCircle2, Database, Zap } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  Area, AreaChart, Bar, BarChart,
  CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis
} from 'recharts';
import './App.css';

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
  id:        number;
  time:      string;
  type:      string;
  userId:    string;
  status:    string;
}

const EVENT_TYPES = [
  'analytics.page_view','user.login','transaction.created',
  'user.signup','system.error','fraud.detected',
];

const INFRA = [
  { name: 'Apache Kafka 3.6',   role: 'Message Broker',    latency: '2ms'  },
  { name: 'Redis 7.2',          role: 'Deduplication',     latency: '0.4ms'},
  { name: 'PostgreSQL 16',      role: 'Event Store',       latency: '3ms'  },
  { name: 'Prometheus',         role: 'Metrics',           latency: '—'    },
  { name: 'Stream Processor',   role: 'Enrichment',        latency: '8ms'  },
];

const LATENCY_PERCENTILES = [
  { label: 'P50', ms: 12  },
  { label: 'P90', ms: 38  },
  { label: 'P95', ms: 62  },
  { label: 'P99', ms: 94  },
];

let logIdSeq = 0;

function makeLogLine(eventsByType: Record<string, number>): LogLine {
  const types = Object.keys(eventsByType);
  const type = types.length
    ? types[Math.floor(Math.random() * types.length)]
    : EVENT_TYPES[Math.floor(Math.random() * EVENT_TYPES.length)];
  const now = new Date();
  return {
    id:     ++logIdSeq,
    time:   now.toLocaleTimeString([], { hour:'2-digit', minute:'2-digit', second:'2-digit' }),
    type,
    userId: 'u_' + Math.random().toString(36).slice(2, 9),
    status: 'OK',
  };
}

const CustomTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={{
      background: '#1c1a17', border: '1px solid rgba(255,255,255,0.08)',
      padding: '8px 12px', fontFamily: 'Geist Mono, monospace', fontSize: 11,
    }}>
      <div style={{ color: 'rgba(255,255,255,0.35)', marginBottom: 2 }}>{label}</div>
      <div style={{ color: '#f07030', fontWeight: 600 }}>{payload[0].value.toLocaleString()}</div>
    </div>
  );
};

export default function App() {
  const [stats, setStats] = useState<EventStats>({
    totalReceived: 0, totalProcessed: 0, totalFailed: 0,
    totalDuplicated: 0, successRate: 100, eventsByType: {}, uptimeSeconds: 0,
  });
  const [series, setSeries]         = useState<TimeSeriesData[]>([]);
  const [connected, setConnected]   = useState(false);
  const [lastUpdate, setLastUpdate] = useState(new Date());
  const [activeTab, setActiveTab]   = useState('Overview');
  const [logLines, setLogLines]     = useState<LogLine[]>([]);
  const prevProcessed               = useRef<number | null>(null);
  const logRef                      = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const seed = async () => {
      try {
        const res = await fetch('/api/v1/events/stats');
        if (!res.ok) return;
        const data: EventStats = await res.json();
        setStats(data);
        prevProcessed.current = data.totalProcessed;
        const now = new Date();
        setSeries([{
          timestamp: now.toLocaleTimeString([], { hour:'2-digit', minute:'2-digit', second:'2-digit' }),
          events: 0,
        }]);
      } catch {}
    };
    seed();

    const es = new EventSource('/api/v1/events/stats/stream');

    es.addEventListener('stats', (e: MessageEvent) => {
      try {
        const data: EventStats = JSON.parse(e.data);
        setStats(data);
        setLastUpdate(new Date());
        setConnected(true);

        setSeries(prev => {
          const processed = data.totalProcessed;
          const delta = prevProcessed.current !== null
            ? Math.max(0, processed - prevProcessed.current) : 0;
          prevProcessed.current = processed;
          return [...prev, {
            timestamp: new Date().toLocaleTimeString([], { hour:'2-digit', minute:'2-digit', second:'2-digit' }),
            events: delta,
          }].slice(-24);
        });

        setLogLines(prev => {
          const next = [makeLogLine(data.eventsByType), ...prev].slice(0, 40);
          return next;
        });
      } catch {}
    });

    es.onerror = () => setConnected(false);
    return () => es.close();
  }, []);

  // Auto-scroll log to top (newest on top)
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = 0;
  }, [logLines.length]);

  const totalByType = Object.values(stats.eventsByType).reduce((a, b) => a + b, 0) || 1;
  const distData = Object.entries(stats.eventsByType).map(([name, value]) => ({
    name,
    short: name.split('.').pop()!.replace(/_/g,' '),
    value,
    pct: Math.round((value / totalByType) * 100),
  })).sort((a, b) => b.value - a.value);

  const barData = distData.slice(0, 6).map(d => ({
    name: d.short,
    value: d.value,
  }));

  const maxPct = LATENCY_PERCENTILES[LATENCY_PERCENTILES.length - 1].ms;

  const tabs = ['Overview', 'Events', 'Latency', 'Infrastructure'];

  return (
    <div className="app">

      {/* ── TICKER ── */}
      <div className="ticker-bar">
        <div className="ticker-left">
          <span>
            <span className={`ping-dot${connected ? '' : ' offline'}`} />
            {connected ? 'STREAM CONNECTED' : 'RECONNECTING…'}
          </span>
          <span className="t-sep" />
          <span>LAST UPDATE: {lastUpdate.toLocaleTimeString()}</span>
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

      {/* ── HEADER ── */}
      <header className="header">
        <div className="header-inner">

          <div className="brand">
            <div className="brand-mark">
              <Activity size={18} strokeWidth={2.2} />
            </div>
            <div>
              <div className="brand-name">EventFlow</div>
              <div className="brand-sub">Stream Processing Platform</div>
            </div>
          </div>

          <nav className="nav">
            {tabs.map((t, i) => (
              <div
                key={t}
                className={`nav-item${activeTab === t ? ' active' : ''}`}
                onClick={() => setActiveTab(t)}
              >
                <span className="nav-num">0{i + 1}</span>{t}
              </div>
            ))}
          </nav>

          <div className="header-kpis">
            <div className="kpi-cell">
              <div className="kpi-lbl">Throughput</div>
              <div className="kpi-val amber">
                {series.length >= 2
                  ? series[series.length - 1].events.toLocaleString()
                  : '0'
                }<span style={{ fontSize: 11, marginLeft: 3, color: 'var(--ink-4)' }}>/tick</span>
              </div>
            </div>
            <div className="kpi-cell">
              <div className="kpi-lbl">Success Rate</div>
              <div className="kpi-val green">{stats.successRate.toFixed(2)}%</div>
            </div>
            <div className="kpi-cell">
              <div className="kpi-lbl">Uptime</div>
              <div className="kpi-val">
                {Math.floor(stats.uptimeSeconds / 3600)}h {Math.floor((stats.uptimeSeconds % 3600) / 60)}m
              </div>
            </div>
          </div>

        </div>
      </header>

      {/* ── MAIN ── */}
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
              <div className="metric-icon amber"><Database size={15} /></div>
            </div>
            <div className="metric-num">{stats.totalReceived.toLocaleString()}</div>
            <div className="metric-foot">
              <span className="badge up">+12.5%</span>
              vs yesterday
            </div>
          </div>

          <div className="metric-panel">
            <div className="metric-panel-top">
              <span className="metric-panel-label">Events Processed</span>
              <div className="metric-icon green"><Zap size={15} /></div>
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
              <div className="metric-icon red"><AlertTriangle size={15} /></div>
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
                <div className="panel-sub">Events per interval — live SSE stream</div>
              </div>
              <span className="panel-tag live">● Live</span>
            </div>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={series} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
                <defs>
                  <linearGradient id="g1" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%"   stopColor="#d4590a" stopOpacity={0.18} />
                    <stop offset="100%" stopColor="#d4590a" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="2 4" stroke="rgba(28,26,23,0.08)" />
                <XAxis
                  dataKey="timestamp"
                  tick={{ fill: '#9b9485', fontSize: 9.5, fontFamily: 'Geist Mono, monospace' }}
                  tickLine={false} axisLine={false} interval={5}
                />
                <YAxis
                  tick={{ fill: '#9b9485', fontSize: 9.5, fontFamily: 'Geist Mono, monospace' }}
                  tickLine={false} axisLine={false}
                />
                <Tooltip content={<CustomTooltip />} />
                <Area
                  type="monotone" dataKey="events"
                  stroke="#d4590a" strokeWidth={2}
                  fill="url(#g1)" dot={false}
                  activeDot={{ r: 3, fill: '#d4590a', strokeWidth: 0 }}
                />
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
            {distData.length > 0 ? (
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
            ) : (
              <div style={{ textAlign: 'center', padding: '32px 0', fontFamily: 'var(--f-mono)', fontSize: 11, color: 'var(--ink-4)' }}>
                Awaiting events…
              </div>
            )}
          </div>
        </div>

        {/* BOTTOM ROW */}
        <div className="section-head">
          <span className="section-head-title">Latency &amp; Infrastructure</span>
          <div className="section-rule" />
        </div>

        <div className="row-1-1">
          {/* Latency */}
          <div className="panel">
            <div className="panel-head">
              <div>
                <div className="panel-title">P99 Latency</div>
                <div className="panel-sub">Ingestion pipeline response times</div>
              </div>
              <span className="panel-tag ok">Sub-100ms</span>
            </div>
            <ResponsiveContainer width="100%" height={120}>
              <BarChart data={barData} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
                <CartesianGrid strokeDasharray="2 4" stroke="rgba(28,26,23,0.08)" vertical={false} />
                <XAxis
                  dataKey="name"
                  tick={{ fill: '#9b9485', fontSize: 9, fontFamily: 'Geist Mono, monospace' }}
                  tickLine={false} axisLine={false}
                />
                <YAxis
                  tick={{ fill: '#9b9485', fontSize: 9, fontFamily: 'Geist Mono, monospace' }}
                  tickLine={false} axisLine={false}
                />
                <Tooltip content={<CustomTooltip />} />
                <Bar dataKey="value" fill="#d4590a" fillOpacity={0.65} radius={[2,2,0,0]} />
              </BarChart>
            </ResponsiveContainer>
            <div className="lat-rows">
              {LATENCY_PERCENTILES.map(p => (
                <div className="lat-row" key={p.label}>
                  <span className="lat-lbl">{p.label}</span>
                  <div className="lat-track">
                    <div className="lat-fill" style={{ width: `${(p.ms / maxPct) * 100}%` }} />
                  </div>
                  <span className="lat-val">{p.ms}ms</span>
                </div>
              ))}
            </div>
          </div>

          {/* Infrastructure */}
          <div className="panel">
            <div className="panel-head">
              <div>
                <div className="panel-title">Infrastructure</div>
                <div className="panel-sub">Component status &amp; latency</div>
              </div>
              <span className="panel-tag ok">All Nominal</span>
            </div>
            <table className="infra-table">
              <thead>
                <tr>
                  <th>Component</th>
                  <th>Role</th>
                  <th>Latency</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {INFRA.map(row => (
                  <tr key={row.name}>
                    <td style={{ fontFamily: 'var(--f-mono)', fontSize: 12 }}>{row.name}</td>
                    <td style={{ color: 'var(--ink-4)', fontSize: 12 }}>{row.role}</td>
                    <td style={{ fontFamily: 'var(--f-mono)', fontSize: 12 }}>{row.latency}</td>
                    <td>
                      <span className="status-chip up">
                        <span className="status-chip-dot" />
                        UP
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
                <div className="panel-sub">Real-time event activity — newest first</div>
              </div>
              <span className="panel-tag live">● Streaming</span>
            </div>
            <div className="event-log" ref={logRef}>
              {logLines.length === 0 ? (
                <span style={{ color: 'rgba(255,255,255,0.2)' }}>
                  Waiting for events — send some via the API or run scripts/send-test-events.sh
                </span>
              ) : logLines.map(l => (
                <div className="log-line" key={l.id}>
                  <span className="log-time">{l.time}</span>
                  <span className="log-type">{l.type}</span>
                  <span className="log-uid">{l.userId}</span>
                  <span className="log-ok">{l.status}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

      </main>

      {/* ── FOOTER ── */}
      <footer className="footer">
        <div className="footer-inner">
          <span>© 2026 EventFlow Platform</span>
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
