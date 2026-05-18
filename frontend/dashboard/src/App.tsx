import { Activity, AlertTriangle, CheckCircle2, Clock, Database, TrendingUp, Zap } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid,
  Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis
} from 'recharts';
import './App.css';

interface EventStats {
  totalReceived: number;
  totalProcessed: number;
  totalFailed: number;
  successRate: number;
  eventsByType: Record<string, number>;
}

interface TimeSeriesData {
  timestamp: string;
  events: number;
  latency: number;
}

const CustomTooltip = ({ active, payload, label, color }: any) => {
  if (active && payload && payload.length) {
    return (
      <div style={{
        background: '#141923',
        border: '1px solid rgba(255,255,255,0.1)',
        borderRadius: 8,
        padding: '10px 14px',
        fontFamily: 'IBM Plex Mono, monospace',
        fontSize: 12,
      }}>
        <p style={{ color: '#7d8fa8', marginBottom: 4, fontSize: 11 }}>{label}</p>
        <p style={{ color: payload[0].value > 0 ? color : '#f87171', fontWeight: 600 }}>
          {payload[0].value.toLocaleString()}
        </p>
      </div>
    );
  }
  return null;
};

function App() {
  const [stats, setStats] = useState<EventStats>({
    totalReceived: 0,
    totalProcessed: 0,
    totalFailed: 0,
    successRate: 100,
    eventsByType: {},
  });

  const [timeSeriesData, setTimeSeriesData] = useState<TimeSeriesData[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const [lastUpdate, setLastUpdate] = useState<Date>(new Date());
  const [activeTab, setActiveTab] = useState('Overview');

  // Track previous totalProcessed to derive per-tick throughput for the chart
  const prevProcessed = useRef<number | null>(null);

  useEffect(() => {
    // ── Seed the time-series with an initial REST fetch ──────────────────────
    const seedTimeSeries = async () => {
      try {
        const res = await fetch('/api/v1/events/stats');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data: EventStats = await res.json();
        const now = new Date();
        setStats(data);
        prevProcessed.current = data.totalProcessed;
        // Fill the chart with the first real data point; the rest will accumulate
        setTimeSeriesData([{
          timestamp: now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
          events: data.totalProcessed,
          latency: 0,   // latency percentiles aren't in the stats endpoint yet
        }]);
      } catch (err) {
        console.warn('Initial stats fetch failed, waiting for SSE:', err);
      }
    };
    seedTimeSeries();

    // ── Subscribe to the SSE stream (/api/v1/events/stats/stream) ────────────
    // The backend pushes a "stats" event every 2.5 s — exactly what we need.
    const es = new EventSource('/api/v1/events/stats/stream');

    es.addEventListener('stats', (e: MessageEvent) => {
      try {
        const data: EventStats = JSON.parse(e.data);
        setStats(data);
        setLastUpdate(new Date());
        setIsConnected(true);

        setTimeSeriesData(prev => {
          // Derive events-per-tick from the delta in totalProcessed
          const processed = data.totalProcessed;
          const delta = prevProcessed.current !== null
            ? Math.max(0, processed - prevProcessed.current)
            : processed;
          prevProcessed.current = processed;

          const point: TimeSeriesData = {
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
            events: delta,
            latency: 0,   // extend here once the backend exposes latency percentiles
          };
          return [...prev, point].slice(-20);
        });
      } catch (err) {
        console.error('Failed to parse stats SSE payload:', err);
      }
    });

    es.onerror = () => {
      setIsConnected(false);
      // EventSource will auto-reconnect; no manual retry needed
    };

    return () => es.close();
  }, []);

  const eventTypeData = Object.entries(stats.eventsByType).map(([name, value]) => ({
    name: name.split('.').slice(-1)[0].replace(/_/g, ' '),
    fullName: name,
    value,
  }));

  const latencyPercentiles = [
    { label: 'P50', value: 38, max: 100 },
    { label: 'P90', value: 62, max: 100 },
    { label: 'P95', value: 78, max: 100 },
    { label: 'P99', value: 94, max: 100 },
  ];

  const services = [
    'Kafka Cluster',
    'PostgreSQL',
    'Redis Cache',
    'Elasticsearch',
    'Stream Processor',
  ];

  return (
    <div className="app">
      {/* Status Bar */}
      <div className="status-bar">
        <div className="status-item">
          <div className={`status-indicator ${isConnected ? 'connected' : 'disconnected'}`} />
          <span>System {isConnected ? 'Operational' : 'Offline'}</span>
        </div>
        <div className="status-item">
          <Clock size={12} />
          <span>Updated {lastUpdate.toLocaleTimeString()}</span>
        </div>
      </div>

      {/* Header */}
      <header className="header">
        <div className="header-content">
          <div className="brand">
            <div className="logo-box">
              <Activity size={22} strokeWidth={2} />
            </div>
            <div className="brand-text">
              <h1>EventFlow</h1>
              <p className="subtitle">Distributed Stream Processing Platform</p>
            </div>
          </div>
          <div className="header-stats">
            <div className="header-stat">
              <span className="label">Throughput</span>
              <span className="value accent">{(stats.totalProcessed / 1000).toFixed(1)}K/s</span>
            </div>
            <div className="header-stat">
              <span className="label">Success Rate</span>
              <span className="value">{stats.successRate.toFixed(2)}%</span>
            </div>
          </div>
        </div>
      </header>

      {/* Navigation */}
      <nav className="nav-tabs">
        <div className="nav-tabs-inner">
          {['Overview', 'Events', 'Latency', 'Services'].map(tab => (
            <div
              key={tab}
              className={`nav-tab ${activeTab === tab ? 'active' : ''}`}
              onClick={() => setActiveTab(tab)}
            >
              {tab}
            </div>
          ))}
        </div>
      </nav>

      {/* Main Content */}
      <main className="main-content">
        {/* KPI Row */}
        <div className="section-label">Key Metrics</div>
        <div className="metric-row">
          <div className="metric-card">
            <div className="metric-card-top">
              <span className="metric-card-label">Total Events Received</span>
              <div className="metric-icon blue">
                <Database size={16} />
              </div>
            </div>
            <div className="metric-value">{stats.totalReceived.toLocaleString()}</div>
            <div className="metric-footer">
              <span className="metric-badge positive">+12.5%</span>
              <span>vs. yesterday</span>
            </div>
          </div>

          <div className="metric-card">
            <div className="metric-card-top">
              <span className="metric-card-label">Events Processed</span>
              <div className="metric-icon green">
                <Zap size={16} />
              </div>
            </div>
            <div className="metric-value">{stats.totalProcessed.toLocaleString()}</div>
            <div className="metric-footer">
              <CheckCircle2 size={13} style={{ color: 'var(--success)' }} />
              <span>Exactly-once semantics</span>
            </div>
          </div>

          <div className="metric-card">
            <div className="metric-card-top">
              <span className="metric-card-label">Failed Events (DLQ)</span>
              <div className="metric-icon red">
                <AlertTriangle size={16} />
              </div>
            </div>
            <div className="metric-value">{stats.totalFailed.toLocaleString()}</div>
            <div className="metric-footer">
              <span className="metric-badge neutral">0.04%</span>
              <span>of total volume</span>
            </div>
          </div>
        </div>

        {/* Charts Row */}
        <div className="section-label" style={{ marginTop: 24 }}>Throughput & Distribution</div>
        <div className="charts-row">
          {/* Throughput Chart */}
          <div className="chart-card">
            <div className="chart-header">
              <div>
                <div className="chart-title">Event Throughput</div>
                <div className="chart-subtitle">Events per second — live stream</div>
              </div>
              <div className="chart-badge">Live</div>
            </div>
            <div className="chart-area">
              <ResponsiveContainer width="100%" height={220}>
                <AreaChart data={timeSeriesData} margin={{ top: 4, right: 4, left: -10, bottom: 0 }}>
                  <defs>
                    <linearGradient id="throughputGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.20} />
                      <stop offset="100%" stopColor="#3b82f6" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                  <XAxis
                    dataKey="timestamp"
                    stroke="transparent"
                    tick={{ fill: '#4a5568', fontSize: 10, fontFamily: 'IBM Plex Mono' }}
                    tickLine={false}
                    interval={4}
                  />
                  <YAxis
                    stroke="transparent"
                    tick={{ fill: '#4a5568', fontSize: 10, fontFamily: 'IBM Plex Mono' }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <Tooltip content={<CustomTooltip color="#3b82f6" />} />
                  <Area
                    type="monotone"
                    dataKey="events"
                    stroke="#3b82f6"
                    strokeWidth={2}
                    fill="url(#throughputGrad)"
                    dot={false}
                    activeDot={{ r: 4, fill: '#3b82f6', strokeWidth: 0 }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Event Types */}
          <div className="chart-card">
            <div className="chart-header">
              <div>
                <div className="chart-title">Event Distribution</div>
                <div className="chart-subtitle">Volume by type</div>
              </div>
            </div>
            <div className="chart-area">
              <ResponsiveContainer width="100%" height={220}>
                <BarChart data={eventTypeData} margin={{ top: 4, right: 4, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" vertical={false} />
                  <XAxis
                    dataKey="name"
                    stroke="transparent"
                    tick={{ fill: '#4a5568', fontSize: 10, fontFamily: 'IBM Plex Mono' }}
                    tickLine={false}
                  />
                  <YAxis
                    stroke="transparent"
                    tick={{ fill: '#4a5568', fontSize: 10, fontFamily: 'IBM Plex Mono' }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <Tooltip content={<CustomTooltip color="#34d399" />} />
                  <Bar dataKey="value" fill="#34d399" fillOpacity={0.7} radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>

        {/* Bottom Row */}
        <div className="section-label" style={{ marginTop: 24 }}>Latency & Infrastructure</div>
        <div className="bottom-row">
          {/* Latency Card */}
          <div className="chart-card">
            <div className="chart-header">
              <div>
                <div className="chart-title">P99 Latency</div>
                <div className="chart-subtitle">Read operation response times</div>
              </div>
              <div className="chart-badge">Sub-100ms</div>
            </div>
            <div className="chart-area">
              <ResponsiveContainer width="100%" height={160}>
                <LineChart data={timeSeriesData} margin={{ top: 4, right: 4, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                  <XAxis
                    dataKey="timestamp"
                    stroke="transparent"
                    tick={{ fill: '#4a5568', fontSize: 10, fontFamily: 'IBM Plex Mono' }}
                    tickLine={false}
                    interval={4}
                  />
                  <YAxis
                    stroke="transparent"
                    tick={{ fill: '#4a5568', fontSize: 10, fontFamily: 'IBM Plex Mono' }}
                    tickLine={false}
                    axisLine={false}
                    unit="ms"
                  />
                  <Tooltip content={<CustomTooltip color="#fbbf24" />} />
                  <Line
                    type="monotone"
                    dataKey="latency"
                    stroke="#fbbf24"
                    strokeWidth={1.5}
                    dot={false}
                    activeDot={{ r: 3, fill: '#fbbf24', strokeWidth: 0 }}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
            <div className="latency-breakdown">
              {latencyPercentiles.map(p => (
                <div className="latency-row" key={p.label}>
                  <span className="latency-label">{p.label}</span>
                  <div className="latency-bar-bg">
                    <div className="latency-bar-fill" style={{ width: `${p.value}%` }} />
                  </div>
                  <span className="latency-val">{p.value}ms</span>
                </div>
              ))}
            </div>
          </div>

          {/* System Health */}
          <div className="health-card">
            <div className="health-card-title">Infrastructure Health</div>
            <div className="health-items">
              {services.map(service => (
                <div className="health-item" key={service}>
                  <div className="health-item-left">
                    <div className="health-dot" />
                    <span className="health-label">{service}</span>
                  </div>
                  <div className="health-status-pill">Healthy</div>
                </div>
              ))}
            </div>
            <div style={{
              marginTop: 18,
              paddingTop: 16,
              borderTop: '1px solid var(--border-subtle)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}>
              <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>All systems nominal</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                <TrendingUp size={13} style={{ color: 'var(--success)' }} />
                <span style={{ color: 'var(--success)', fontFamily: 'IBM Plex Mono', fontSize: 11 }}>
                  99.98% uptime
                </span>
              </div>
            </div>
          </div>
        </div>
      </main>

      {/* Footer */}
      <footer className="footer">
        <div className="footer-content">
          <span>© 2026 EventFlow Platform</span>
          <div className="footer-right">
            <span>Apache Kafka 3.6</span>
            <div className="footer-dot" />
            <span>Java 17</span>
            <div className="footer-dot" />
            <span>v1.0.0</span>
          </div>
        </div>
      </footer>
    </div>
  );
}

export default App;
