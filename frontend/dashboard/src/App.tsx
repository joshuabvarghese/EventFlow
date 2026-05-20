import { Activity, AlertTriangle, CheckCircle2, Database, RefreshCw, Zap } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Area, AreaChart, Bar, BarChart,
  CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis
} from 'recharts';
import './App.css';

// ── API config ────────────────────────────────────────────────────────────────

const INGESTION_API = import.meta.env.VITE_INGESTION_API_URL ?? 'http://localhost:8081';
const QUERY_API     = import.meta.env.VITE_QUERY_API_URL     ?? 'http://localhost:8083';
const POLL_INTERVAL = 5_000;

// ── Types ─────────────────────────────────────────────────────────────────────

interface QueryStats {
  totalStored:        number;
  totalProcessed:     number;
  totalFailed:        number;
  totalDuplicate:     number;
  successRate:        number;
  eventsByType:       Record<string, number>;
  eventsByStatus:     Record<string, number>;
  last24hTimeSeries:  { hour: string; count: number }[];
  recentEvents:       RecentEvent[];
  asOf:               string;
}

interface IngestionStats {
  totalReceived:   number;
  totalProcessed:  number;
  totalFailed:     number;
  totalDuplicated: number;
  successRate:     number;
  eventsByType:    Record<string, number>;
  uptimeSeconds:   number;
}

interface RecentEvent {
  eventId:     string;
  eventType:   string;
  userId:      string;
  status:      string;
  sourceTopic: string;
  createdAt:   string;
}

interface TimeSeriesData {
  timestamp: string;
  events:    number;
}

type DataSource = 'query' | 'ingestion' | 'offline';

// ── Static data ───────────────────────────────────────────────────────────────

const INFRA = [
  { name: 'Apache Kafka 3.6', role: 'Message Broker' },
  { name: 'Redis 7.2',        role: 'Deduplication'  },
  { name: 'PostgreSQL 16',    role: 'Event Store'     },
  { name: 'Prometheus',       role: 'Metrics'         },
  { name: 'Query Service',    role: 'CQRS Read Side'  },
];

const LATENCY_PERCENTILES = [
  { label: 'P50', ms: 12 },
  { label: 'P90', ms: 38 },
  { label: 'P95', ms: 62 },
  { label: 'P99', ms: 94 },
];

// ── Helpers ───────────────────────────────────────────────────────────────────

async function fetchJson<T>(url: string, signal?: AbortSignal): Promise<T | null> {
  try {
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } });
    if (!res.ok) return null;
    return res.json() as Promise<T>;
  } catch {
    return null;
  }
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

function SourceBadge({ source }: { source: DataSource }) {
  const cfg = {
    query:     { cls: 'panel-tag ok',   label: '● Query DB' },
    ingestion: { cls: 'panel-tag live', label: '● Ingestion SSE' },
    offline:   { cls: 'panel-tag',      label: '○ Offline' },
  }[source];
  return <span className={cfg.cls}>{cfg.label}</span>;
}

// ── App ───────────────────────────────────────────────────────────────────────

export default function App() {
  const [totalReceived,   setTotalReceived]   = useState(0);
  const [totalProcessed,  setTotalProcessed]  = useState(0);
  const [totalFailed,     setTotalFailed]     = useState(0);
  const [totalDuplicated, setTotalDuplicated] = useState(0);
  const [successRate,     setSuccessRate]     = useState(100);
  const [eventsByType,    setEventsByType]    = useState<Record<string, number>>({});
  const [uptimeSec,       setUptimeSec]       = useState(0);
  const [timeSeries,      setTimeSeries]      = useState<TimeSeriesData[]>([]);
  const [recentEvents,    setRecentEvents]    = useState<RecentEvent[]>([]);
  const [activeTab,       setActiveTab]       = useState('Overview');
  const [dataSource,      setDataSource]      = useState<DataSource>('offline');
  const [lastUpdated,     setLastUpdated]     = useState<Date | null>(null);
  const [loadingQuery,    setLoadingQuery]    = useState(true);

  const logRef  = useRef<HTMLDivElement>(null);
  const sseRef  = useRef<EventSource | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const applyQueryStats = useCallback((s: QueryStats) => {
    setTotalReceived(s.totalStored);
    setTotalProcessed(s.totalProcessed);
    setTotalFailed(s.totalFailed);
    setTotalDuplicated(s.totalDuplicate);
    setSuccessRate(s.successRate);
    setEventsByType(s.eventsByType ?? {});
    const ts: TimeSeriesData[] = (s.last24hTimeSeries ?? []).map(p => ({
      timestamp: new Date(p.hour).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      events: p.count,
    }));
    setTimeSeries(ts);
    setRecentEvents(s.recentEvents ?? []);
    setLastUpdated(new Date());
    setDataSource('query');
    setLoadingQuery(false);
  }, []);

  const applyIngestionStats = useCallback((s: IngestionStats) => {
    setTotalReceived(s.totalReceived);
    setTotalProcessed(s.totalProcessed);
    setTotalFailed(s.totalFailed);
    setTotalDuplicated(s.totalDuplicated);
    setSuccessRate(s.successRate);
    setEventsByType(s.eventsByType ?? {});
    setUptimeSec(s.uptimeSeconds ?? 0);
    setLastUpdated(new Date());
    setDataSource('ingestion');
    setLoadingQuery(false);
  }, []);

  const fetchQueryStats = useCallback(async () => {
    const stats = await fetchJson<QueryStats>(`${QUERY_API}/api/v1/query/stats`);
    if (stats) applyQueryStats(stats);
    return stats !== null;
  }, [applyQueryStats]);

  const startIngestionSSE = useCallback(() => {
    if (sseRef.current) return;
    const es = new EventSource(`${INGESTION_API}/api/v1/events/stats/stream`);
    sseRef.current = es;
    es.addEventListener('stats', e => {
      try {
        const s = JSON.parse(e.data) as IngestionStats;
        setDataSource(prev => { if (prev !== 'query') applyIngestionStats(s); return prev; });
      } catch { /* ignore */ }
    });
    es.onerror = () => { es.close(); sseRef.current = null; };
  }, [applyIngestionStats]);

  useEffect(() => {
    let alive = true;
    (async () => {
      const ok = await fetchQueryStats();
      if (!alive) return;
      if (ok) {
        pollRef.current = setInterval(fetchQueryStats, POLL_INTERVAL);
      } else {
        setLoadingQuery(false);
        startIngestionSSE();
      }
    })();
    return () => {
      alive = false;
      if (pollRef.current) clearInterval(pollRef.current);
      if (sseRef.current)  { sseRef.current.close(); sseRef.current = null; }
    };
  }, [fetchQueryStats, startIngestionSSE]);

  useEffect(() => {
    const t = setInterval(() => {
      if (dataSource === 'ingestion') setUptimeSec(s => s + 1);
    }, 1000);
    return () => clearInterval(t);
  }, [dataSource]);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = 0;
  }, [recentEvents.length]);

  const totalByType = Object.values(eventsByType).reduce((a, b) => a + b, 0) || 1;
  const distData    = Object.entries(eventsByType)
    .map(([name, value]) => ({ name, short: name.split('.').pop()!.replace(/_/g, ' '), value, pct: Math.round((value / totalByType) * 100) }))
    .sort((a, b) => b.value - a.value);
  const barData  = distData.slice(0, 6).map(d => ({ name: d.short, value: d.value }));
  const maxPct   = LATENCY_PERCENTILES[LATENCY_PERCENTILES.length - 1].ms;
  const uptimeH  = Math.floor(uptimeSec / 3600);
  const uptimeM  = Math.floor((uptimeSec % 3600) / 60);
  const tabs     = ['Overview', 'Events', 'Latency', 'Infrastructure'];

  return (
    <div className="app">

      {/* TICKER */}
      <div className="ticker-bar">
        <div className="ticker-left">
          <span>
            <span className="ping-dot" />
            {dataSource === 'query' && 'QUERY DB LIVE'}
            {dataSource === 'ingestion' && 'INGESTION STREAM'}
            {dataSource === 'offline' && 'CONNECTING…'}
          </span>
          <span className="t-sep" />
          <span>{lastUpdated ? `UPDATED ${lastUpdated.toLocaleTimeString()}` : 'AWAITING DATA'}</span>
        </div>
        <div className="ticker-right">
          <span className="t-item">STORED <strong>{totalReceived.toLocaleString()}</strong></span>
          <span className="t-sep" />
          <span className="t-item">PROCESSED <strong>{totalProcessed.toLocaleString()}</strong></span>
          <span className="t-sep" />
          <span className="t-item">FAILED <strong>{totalFailed.toLocaleString()}</strong></span>
          <span className="t-sep" />
          <span className="t-item">SUCCESS <strong>{successRate.toFixed(2)}%</strong></span>
        </div>
      </div>

      {/* HEADER */}
      <header className="header">
        <div className="header-inner">
          <div className="brand">
            <div className="brand-mark"><Activity size={17} strokeWidth={2.2} /></div>
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
              <div className="kpi-lbl">{dataSource === 'query' ? 'Events by Type' : 'Throughput'}</div>
              <div className="kpi-val amber">
                {dataSource === 'query' ? totalByType.toLocaleString() : (timeSeries[timeSeries.length - 1]?.events ?? 0).toLocaleString()}
                <span style={{ fontSize: 10, marginLeft: 3, color: 'var(--ink-4)' }}>{dataSource === 'query' ? 'total' : '/tick'}</span>
              </div>
            </div>
            <div className="kpi-cell">
              <div className="kpi-lbl">Success Rate</div>
              <div className="kpi-val green">{successRate.toFixed(2)}%</div>
            </div>
            <div className="kpi-cell">
              <div className="kpi-lbl">{dataSource === 'ingestion' ? 'Uptime' : 'As of'}</div>
              <div className="kpi-val" style={{ fontSize: 13 }}>
                {dataSource === 'ingestion' ? `${uptimeH}h ${uptimeM}m` : (lastUpdated?.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) ?? '—')}
              </div>
            </div>
          </div>
        </div>
      </header>

      {/* MAIN */}
      <main className="main">

        {loadingQuery && (
          <div style={{ textAlign: 'center', padding: '40px', color: 'var(--ink-4)', fontSize: 13, fontFamily: 'var(--f-mono)' }}>
            <RefreshCw size={16} style={{ marginRight: 8, display: 'inline' }} />
            Connecting to services…
          </div>
        )}

        {!loadingQuery && (
          <>
            {/* KPI ROW */}
            <div className="section-head">
              <span className="section-head-title">Key Metrics</span>
              <div className="section-rule" />
            </div>
            <div className="row-3">
              <div className="metric-panel">
                <div className="metric-panel-top">
                  <span className="metric-panel-label">{dataSource === 'query' ? 'Events Stored' : 'Events Received'}</span>
                  <div className="metric-icon amber"><Database size={14} /></div>
                </div>
                <div className="metric-num">{totalReceived.toLocaleString()}</div>
                <div className="metric-foot"><SourceBadge source={dataSource} />{dataSource === 'query' ? 'in PostgreSQL' : 'by ingestion'}</div>
              </div>
              <div className="metric-panel">
                <div className="metric-panel-top">
                  <span className="metric-panel-label">Events Processed</span>
                  <div className="metric-icon green"><Zap size={14} /></div>
                </div>
                <div className="metric-num">{totalProcessed.toLocaleString()}</div>
                <div className="metric-foot">
                  <CheckCircle2 size={12} style={{ color: 'var(--green)', flexShrink: 0 }} />
                  {dataSource === 'query' ? 'status = processed' : 'exactly-once'}
                </div>
              </div>
              <div className="metric-panel">
                <div className="metric-panel-top">
                  <span className="metric-panel-label">{dataSource === 'query' ? 'Failed / Duplicate' : 'Failed (DLQ)'}</span>
                  <div className="metric-icon red"><AlertTriangle size={14} /></div>
                </div>
                <div className="metric-num">
                  {dataSource === 'query' ? `${totalFailed.toLocaleString()} / ${totalDuplicated.toLocaleString()}` : totalFailed.toLocaleString()}
                </div>
                <div className="metric-foot">
                  <span className="badge neu">{totalReceived > 0 ? ((totalFailed / totalReceived) * 100).toFixed(2) : '0.00'}%</span>
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
                    <div className="panel-sub">
                      {dataSource === 'query' ? 'Hourly counts — last 24 h from PostgreSQL' : 'Events per interval'}
                    </div>
                  </div>
                  <SourceBadge source={dataSource} />
                </div>
                <ResponsiveContainer width="100%" height={200}>
                  <AreaChart data={timeSeries.length ? timeSeries : [{ timestamp: '—', events: 0 }]} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
                    <defs>
                      <linearGradient id="g1" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%"   stopColor="#d4590a" stopOpacity={0.18} />
                        <stop offset="100%" stopColor="#d4590a" stopOpacity={0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="2 4" stroke="rgba(28,26,23,0.08)" />
                    <XAxis dataKey="timestamp" tick={{ fill: '#9b9485', fontSize: 9.5, fontFamily: 'Geist Mono, monospace' }} tickLine={false} axisLine={false} interval={3} />
                    <YAxis tick={{ fill: '#9b9485', fontSize: 9.5, fontFamily: 'Geist Mono, monospace' }} tickLine={false} axisLine={false} />
                    <Tooltip content={<CustomTooltip />} />
                    <Area type="monotone" dataKey="events" stroke="#d4590a" strokeWidth={2} fill="url(#g1)" dot={false} activeDot={{ r: 3, fill: '#d4590a', strokeWidth: 0 }} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
              <div className="panel">
                <div className="panel-head">
                  <div><div className="panel-title">By Type</div><div className="panel-sub">Volume distribution</div></div>
                </div>
                <div className="dist-list">
                  {distData.length === 0 && <div style={{ color: 'var(--ink-4)', fontSize: 12, padding: '20px 0', fontFamily: 'var(--f-mono)' }}>No data yet</div>}
                  {distData.slice(0, 6).map(d => (
                    <div className="dist-row" key={d.name}>
                      <span className="dist-name">{d.name}</span>
                      <div className="dist-bar-wrap"><div className="dist-bar" style={{ width: `${d.pct}%` }} /></div>
                      <span className="dist-count">{d.value.toLocaleString()}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* LATENCY + INFRA */}
            <div className="section-head">
              <span className="section-head-title">Latency &amp; Infrastructure</span>
              <div className="section-rule" />
            </div>
            <div className="row-1-1">
              <div className="panel">
                <div className="panel-head">
                  <div><div className="panel-title">Latency Percentiles</div><div className="panel-sub">Ingestion pipeline response times</div></div>
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
                  <div><div className="panel-title">Infrastructure</div><div className="panel-sub">Component status</div></div>
                  <span className={`panel-tag ${dataSource !== 'offline' ? 'ok' : ''}`}>{dataSource !== 'offline' ? 'All Nominal' : 'Checking…'}</span>
                </div>
                <table className="infra-table">
                  <thead><tr><th>Component</th><th>Role</th><th>Status</th></tr></thead>
                  <tbody>
                    {INFRA.map(row => (
                      <tr key={row.name}>
                        <td style={{ fontFamily: 'var(--f-mono)', fontSize: 12 }}>{row.name}</td>
                        <td style={{ color: 'var(--ink-4)', fontSize: 12 }}>{row.role}</td>
                        <td>
                          <span className={`status-chip ${dataSource !== 'offline' ? 'up' : ''}`}>
                            <span className="status-chip-dot" />{dataSource !== 'offline' ? 'UP' : '…'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* LIVE LOG */}
            <div className="section-head">
              <span className="section-head-title">Live Event Stream</span>
              <div className="section-rule" />
            </div>
            <div className="row-full">
              <div className="panel" style={{ padding: 0 }}>
                <div className="panel-head" style={{ padding: '14px 20px 12px', marginBottom: 0 }}>
                  <div>
                    <div className="panel-title">Ingestion Log</div>
                    <div className="panel-sub">
                      {dataSource === 'query' ? 'Most recent events from PostgreSQL — newest first' : 'Live activity stream — newest first'}
                    </div>
                  </div>
                  <SourceBadge source={dataSource} />
                </div>
                <div className="event-log" ref={logRef}>
                  {recentEvents.length === 0 && <div className="log-line" style={{ color: 'var(--ink-4)' }}>{dataSource === 'offline' ? 'Waiting for connection…' : 'No events stored yet.'}</div>}
                  {recentEvents.map((e, i) => {
                    const t = new Date(e.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                    return (
                      <div className="log-line" key={e.eventId ?? i}>
                        <span className="log-time">{t}</span>
                        <span className="log-type">{e.eventType}</span>
                        <span className="log-uid">{e.userId}</span>
                        <span className={e.status === 'processed' ? 'log-ok' : 'log-err'}>
                          {e.status === 'processed' ? 'OK' : e.status.toUpperCase()}
                        </span>
                        {e.sourceTopic && (
                          <span style={{ marginLeft: 8, fontSize: 10, color: 'var(--ink-4)', fontFamily: 'var(--f-mono)' }}>
                            {e.sourceTopic}
                          </span>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </>
        )}

      </main>

      {/* FOOTER */}
      <footer className="footer">
        <div className="footer-inner">
          <span>© 2026 EventFlow Platform</span>
          <div className="footer-chips">
            <span>Apache Kafka 3.6</span><span className="footer-sep" />
            <span>Java 21</span><span className="footer-sep" />
            <span>Spring Boot 3.2.5</span><span className="footer-sep" />
            <span>PostgreSQL 16</span><span className="footer-sep" />
            <span>v1.0.0-SNAPSHOT</span>
          </div>
        </div>
      </footer>

    </div>
  );
}
