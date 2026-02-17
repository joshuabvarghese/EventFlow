import { Activity, AlertCircle, Clock, Database, TrendingUp, Zap } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
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

function App() {
  const [stats, setStats] = useState<EventStats>({
    totalReceived: 0,
    totalProcessed: 0,
    totalFailed: 0,
    successRate: 100,
    eventsByType: {}
  });

  const [timeSeriesData, setTimeSeriesData] = useState<TimeSeriesData[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const [lastUpdate, setLastUpdate] = useState<Date>(new Date());

  useEffect(() => {
    const interval = setInterval(() => {
      const newStat: EventStats = {
        totalReceived: Math.floor(Math.random() * 1000000) + 500000,
        totalProcessed: Math.floor(Math.random() * 980000) + 490000,
        totalFailed: Math.floor(Math.random() * 1000) + 100,
        successRate: 99.2 + Math.random() * 0.8,
        eventsByType: {
          'user.signup': Math.floor(Math.random() * 5000) + 10000,
          'user.login': Math.floor(Math.random() * 15000) + 50000,
          'transaction.created': Math.floor(Math.random() * 8000) + 20000,
          'analytics.page.view': Math.floor(Math.random() * 30000) + 100000,
        }
      };
      setStats(newStat);
      setLastUpdate(new Date());
      setIsConnected(true);

      setTimeSeriesData(prev => {
        const newData = [...prev, {
          timestamp: new Date().toLocaleTimeString(),
          events: Math.floor(Math.random() * 1000) + 5000,
          latency: Math.floor(Math.random() * 50) + 50
        }];
        return newData.slice(-20);
      });
    }, 2000);

    return () => clearInterval(interval);
  }, []);

  const eventTypeData = Object.entries(stats.eventsByType).map(([name, value]) => ({
    name: name.replace(/\./g, ' '),
    value
  }));

  return (
    <div className="app">
      <div className="status-bar">
        <div className="status-item">
          <div className={`status-indicator ${isConnected ? 'connected' : 'disconnected'}`} />
          <span>SYSTEM {isConnected ? 'OPERATIONAL' : 'OFFLINE'}</span>
        </div>
        <div className="status-item">
          <Clock size={14} />
          <span>LAST UPDATE: {lastUpdate.toLocaleTimeString()}</span>
        </div>
      </div>

      <header className="header">
        <div className="header-content">
          <div className="brand">
            <div className="logo-box">
              <Activity size={32} strokeWidth={3} />
            </div>
            <div>
              <h1>Event Flow</h1>
              <p className="subtitle">REAL-TIME EVENT PROCESSING PLATFORM</p>
            </div>
          </div>
          <div className="header-stats">
            <div className="header-stat">
              <span className="label">THROUGHPUT</span>
              <span className="value">{(stats.totalProcessed / 1000).toFixed(1)}K/s</span>
            </div>
            <div className="header-stat">
              <span className="label">SUCCESS RATE</span>
              <span className="value">{stats.successRate.toFixed(2)}%</span>
            </div>
          </div>
        </div>
      </header>

      <main className="main-grid">
        <div className="metric-card primary">
          <div className="metric-icon">
            <Database size={28} strokeWidth={2.5} />
          </div>
          <div className="metric-content">
            <div className="metric-label">TOTAL EVENTS</div>
            <div className="metric-value">{stats.totalReceived.toLocaleString()}</div>
            <div className="metric-change positive">
              <TrendingUp size={14} />
              <span>+12.5% from yesterday</span>
            </div>
          </div>
        </div>

        <div className="metric-card success">
          <div className="metric-icon">
            <Zap size={28} strokeWidth={2.5} />
          </div>
          <div className="metric-content">
            <div className="metric-label">PROCESSED</div>
            <div className="metric-value">{stats.totalProcessed.toLocaleString()}</div>
            <div className="metric-change positive">
              <TrendingUp size={14} />
              <span>Real-time processing</span>
            </div>
          </div>
        </div>

        <div className="metric-card danger">
          <div className="metric-icon">
            <AlertCircle size={28} strokeWidth={2.5} />
          </div>
          <div className="metric-content">
            <div className="metric-label">FAILED</div>
            <div className="metric-value">{stats.totalFailed.toLocaleString()}</div>
            <div className="metric-change neutral">
              <span>Sent to DLQ</span>
            </div>
          </div>
        </div>

        <div className="chart-card wide">
          <div className="chart-header">
            <h2>EVENT THROUGHPUT</h2>
            <div className="chart-legend">
              <span className="legend-item">
                <div className="legend-color primary-color" />
                Events/sec
              </span>
            </div>
          </div>
          <ResponsiveContainer width="100%" height={250}>
            <AreaChart data={timeSeriesData}>
              <defs>
                <linearGradient id="colorEvents" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#00ff88" stopOpacity={0.3}/>
                  <stop offset="95%" stopColor="#00ff88" stopOpacity={0}/>
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#333" />
              <XAxis dataKey="timestamp" stroke="#666" />
              <YAxis stroke="#666" />
              <Tooltip 
                contentStyle={{ background: '#1a1a1a', border: '2px solid #00ff88' }}
                labelStyle={{ color: '#fff' }}
              />
              <Area 
                type="monotone" 
                dataKey="events" 
                stroke="#00ff88" 
                strokeWidth={3}
                fill="url(#colorEvents)" 
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        <div className="chart-card">
          <div className="chart-header">
            <h2>LATENCY (P99)</h2>
          </div>
          <ResponsiveContainer width="100%" height={250}>
            <LineChart data={timeSeriesData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#333" />
              <XAxis dataKey="timestamp" stroke="#666" />
              <YAxis stroke="#666" unit="ms" />
              <Tooltip 
                contentStyle={{ background: '#1a1a1a', border: '2px solid #ff3366' }}
                labelStyle={{ color: '#fff' }}
              />
              <Line 
                type="monotone" 
                dataKey="latency" 
                stroke="#ff3366" 
                strokeWidth={3}
                dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <div className="chart-card">
          <div className="chart-header">
            <h2>EVENT TYPES</h2>
          </div>
          <ResponsiveContainer width="100%" height={250}>
            <BarChart data={eventTypeData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#333" />
              <XAxis dataKey="name" stroke="#666" angle={-45} textAnchor="end" height={80} />
              <YAxis stroke="#666" />
              <Tooltip 
                contentStyle={{ background: '#1a1a1a', border: '2px solid #ffaa00' }}
                labelStyle={{ color: '#fff' }}
              />
              <Bar dataKey="value" fill="#ffaa00" />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div className="health-card">
          <h2>SYSTEM HEALTH</h2>
          <div className="health-items">
            <div className="health-item">
              <span className="health-label">Kafka Cluster</span>
              <div className="health-status operational">OPERATIONAL</div>
            </div>
            <div className="health-item">
              <span className="health-label">PostgreSQL</span>
              <div className="health-status operational">OPERATIONAL</div>
            </div>
            <div className="health-item">
              <span className="health-label">Redis Cache</span>
              <div className="health-status operational">OPERATIONAL</div>
            </div>
            <div className="health-item">
              <span className="health-label">Elasticsearch</span>
              <div className="health-status operational">OPERATIONAL</div>
            </div>
            <div className="health-item">
              <span className="health-label">Stream Processor</span>
              <div className="health-status operational">OPERATIONAL</div>
            </div>
          </div>
        </div>
      </main>

      <footer className="footer">
        <div className="footer-content">
          <span>© 2026 Event Flow PLATFORM</span>
          <span>v1.0.0-SNAPSHOT</span>
        </div>
      </footer>
    </div>
  );
}

export default App;