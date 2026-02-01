import { useState, useEffect, useCallback } from 'react'
import axios from 'axios'
import CombinedChart from './components/CombinedChart'
import DistributionModule from './components/DistributionModule'
import UptrendModule from './components/UptrendModule'
import BacktestModule from './components/BacktestModule'
import OptimizerModule from './components/OptimizerModule'
import DailyOptimizerModule from './components/DailyOptimizerModule'
import DataSyncModule from './components/DataSyncModule'
import StatsCard from './components/StatsCard'
import TimeRangeSelector from './components/TimeRangeSelector'

function App() {
    const [historyData, setHistoryData] = useState([])
    const [stats, setStats] = useState(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState(null)
    const [timeRange, setTimeRange] = useState(168) // 默认7天
    const [autoRefresh, setAutoRefresh] = useState(true)
    const [selectedTimeRange, setSelectedTimeRange] = useState(null) // 刷选的时间区间
    const [missingData, setMissingData] = useState(null) // 数据缺漏信息
    const [showMissingBanner, setShowMissingBanner] = useState(true) // 是否显示缺漏提示
    const [view, setView] = useState('dashboard') // 'dashboard', 'backtest', 'optimizer', 'tools'

    // 处理 Hash 路由
    useEffect(() => {
        const handleHashChange = () => {
            const hash = window.location.hash.replace('#', '')
            if (hash === 'backtest') {
                setView('backtest')
            } else if (hash === 'daily-report') {
                setView('daily-report')
            } else if (hash === 'tools') {
                setView('tools')
            }
            else {
                setView('dashboard')
            }
        }

        window.addEventListener('hashchange', handleHashChange)
        handleHashChange() // 初始化

        return () => window.removeEventListener('hashchange', handleHashChange)
    }, [])

    // 处理图表时间区间选择
    const handleTimeRangeSelect = (range) => {
        console.log('图表时间联动:', range)
        setSelectedTimeRange(range)
    }

    // 检查数据缺漏（首次进入页面时调用）
    useEffect(() => {
        const checkMissingData = async () => {
            try {
                const res = await axios.get('/api/index/missing?days=1')
                // 只有缺漏币种 > 20 才提示（排除新币影响）
                if (res.data.success && res.data.symbolsWithMissing > 20) {
                    // 从详情中提取时间范围
                    const details = res.data.details || []
                    let timeRanges = []
                    details.slice(0, 5).forEach(item => {
                        if (item.missingTimestamps && item.missingTimestamps.length > 0) {
                            // 取第一个和最后一个时间戳作为范围
                            const timestamps = item.missingTimestamps
                            timeRanges.push(timestamps[0])
                        }
                    })
                    // 去重并排序
                    const uniqueTimes = [...new Set(timeRanges)].sort()

                    setMissingData({
                        total: res.data.totalMissingRecords,
                        symbols: res.data.symbolsWithMissing,
                        timeRange: uniqueTimes.length > 0
                            ? `${uniqueTimes[0]} 起`
                            : ''
                    })
                }
            } catch (err) {
                console.error('检查数据缺漏失败:', err)
            }
        }
        checkMissingData()
    }, [])

    const fetchData = useCallback(async () => {
        try {
            setError(null)

            const [historyRes, statsRes] = await Promise.all([
                axios.get(`/api/index/history?hours=${timeRange}`),
                axios.get('/api/index/stats')
            ])

            if (historyRes.data.success) {
                setHistoryData(historyRes.data.data)
            }

            if (statsRes.data.success) {
                setStats(statsRes.data.stats)
            }

            setLoading(false)
        } catch (err) {
            console.error('获取数据失败:', err)
            setError(err.message || '获取数据失败')
            setLoading(false)
        }
    }, [timeRange])

    useEffect(() => {
        fetchData()
    }, [fetchData])

    // 自动刷新
    useEffect(() => {
        if (!autoRefresh) return

        const interval = setInterval(() => {
            fetchData()
        }, 60000) // 每分钟刷新

        return () => clearInterval(interval)
    }, [autoRefresh, fetchData])

    const handleRefresh = () => {
        setLoading(true)
        fetchData()
    }

    const formatPercent = (value) => {
        if (value === null || value === undefined) return '--'
        const prefix = value >= 0 ? '+' : ''
        return `${prefix}${value.toFixed(2)}%`
    }

    const getValueClass = (value) => {
        if (value === null || value === undefined) return ''
        return value >= 0 ? 'positive' : 'negative'
    }

    return (
        <div className="app">
            {/* 顶部导航栏 */}
            <nav className="nav-bar">
                <div className="nav-container">
                    <div className="nav-logo">📊 Binance Market Index</div>
                    <div className="nav-links">
                        <a href="#" className={`nav-link ${view === 'dashboard' ? 'active' : ''}`} onClick={() => setView('dashboard')}>
                            🏠 市场看板
                        </a>
                        <a href="#backtest" className={`nav-link ${view === 'backtest' ? 'active' : ''}`} onClick={() => setView('backtest')}>
                            🧪 策略回测
                        </a>
                        <a href="#daily-report" className={`nav-link ${view === 'daily-report' ? 'active' : ''}`} onClick={() => setView('daily-report')}>
                            📅 每日战报
                        </a>
                        <a href="#tools" className={`nav-link ${view === 'tools' ? 'active' : ''}`} onClick={() => setView('tools')}>
                            🛠️ 数据工具
                        </a>
                    </div>
                </div>
            </nav>

            {/* 数据缺漏提示条 (全局显示) */}
            {missingData && showMissingBanner && (
                <div className="missing-banner">
                    <span>
                        ⚠️ 最近24小时有 <strong>{missingData.total}</strong> 条数据缺漏
                        （{missingData.symbols} 个币种）
                        {missingData.timeRange && (
                            <span className="missing-time">
                                缺漏时段: {missingData.timeRange}
                            </span>
                        )}
                    </span>
                    <button onClick={() => setShowMissingBanner(false)} title="关闭提示">✕</button>
                </div>
            )}

            {/* 市场看板视图 */}
            <div style={{ display: view === 'dashboard' ? 'block' : 'none' }}>
                <>
                    {/* 统计卡片 */}
                    <div className="stats-container">
                        <StatsCard
                            label="📈 当前指数"
                            value={formatPercent(stats?.current)}
                            valueClass={getValueClass(stats?.current)}
                            subValue={stats?.lastUpdate ? `更新于 ${new Date(stats.lastUpdate).toLocaleTimeString()}` : ''}
                        />
                        <StatsCard
                            label="📊 24小时变化"
                            value={formatPercent(stats?.change24h)}
                            valueClass={getValueClass(stats?.change24h)}
                            subValue={stats?.high24h !== undefined ? `高: ${formatPercent(stats.high24h)} / 低: ${formatPercent(stats.low24h)}` : ''}
                        />
                        <StatsCard
                            label="📆 3天变化"
                            value={formatPercent(stats?.change3d)}
                            valueClass={getValueClass(stats?.change3d)}
                            subValue={stats?.high3d !== undefined ? `高: ${formatPercent(stats.high3d)} / 低: ${formatPercent(stats.low3d)}` : ''}
                        />
                        <StatsCard
                            label="📅 7天变化"
                            value={formatPercent(stats?.change7d)}
                            valueClass={getValueClass(stats?.change7d)}
                            subValue={stats?.high7d !== undefined ? `高: ${formatPercent(stats.high7d)} / 低: ${formatPercent(stats.low7d)}` : ''}
                        />
                        <StatsCard
                            label="🗓️ 30天变化"
                            value={formatPercent(stats?.change30d)}
                            valueClass={getValueClass(stats?.change30d)}
                            subValue={stats?.high30d !== undefined ? `高: ${formatPercent(stats.high30d)} / 低: ${formatPercent(stats.low30d)}` : ''}
                        />
                        <StatsCard
                            label="🪙 参与币种"
                            value={stats?.coinCount || '--'}
                            subValue="排除 BTC、ETH"
                        />
                    </div>

                    {/* 控制栏 */}
                    <div className="controls">
                        <TimeRangeSelector
                            value={timeRange}
                            onChange={setTimeRange}
                        />

                        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                            <label className="auto-refresh">
                                <input
                                    type="checkbox"
                                    checked={autoRefresh}
                                    onChange={(e) => setAutoRefresh(e.target.checked)}
                                />
                                自动刷新
                            </label>

                            <button
                                className={`refresh-btn ${loading ? 'loading' : ''}`}
                                onClick={handleRefresh}
                                disabled={loading}
                            >
                                🔄 刷新
                            </button>
                        </div>
                    </div>

                    {/* 图表区域 */}
                    <div className="chart-container">
                        <div className="chart-title">
                            📊 市场指数 & 成交额走势
                        </div>

                        {loading && historyData.length === 0 ? (
                            <div className="loading-container">
                                <div className="loading-spinner"></div>
                                <p>正在加载数据...</p>
                            </div>
                        ) : error ? (
                            <div className="error-container">
                                <div className="error-icon">⚠️</div>
                                <p>{error}</p>
                                <button className="retry-btn" onClick={handleRefresh}>
                                    重试
                                </button>
                            </div>
                        ) : historyData.length === 0 ? (
                            <div className="no-data">
                                <div className="icon">📭</div>
                                <p>暂无数据</p>
                                <p>服务启动后需要等待数据回补完成</p>
                            </div>
                        ) : (
                            <CombinedChart data={historyData} onTimeRangeSelect={handleTimeRangeSelect} />
                        )}
                    </div>

                    {/* 单边上行涨幅模块 */}
                    <UptrendModule />

                    {/* 涨幅分布模块 */}
                    <DistributionModule externalTimeRange={selectedTimeRange} />
                </>
            </div>

            {/* 策略回测视图 */}
            <div style={{ display: view === 'backtest' ? 'block' : 'none', animation: view === 'backtest' ? 'slideUp 0.4s ease-out' : 'none' }}>
                {/* 做空涨幅榜前10回测模块 */}
                <BacktestModule />

                {/* 策略优化器模块 */}
                <OptimizerModule />
            </div>

            {/* 每日策略战报视图 */}
            <div style={{ display: view === 'daily-report' ? 'block' : 'none', animation: view === 'daily-report' ? 'slideUp 0.4s ease-out' : 'none' }}>
                <DailyOptimizerModule />
            </div>

            {/* 数据工具视图 */}
            <div className="view-container" style={{ display: view === 'tools' ? 'block' : 'none' }}>
                <DataSyncModule />
            </div>

            <footer className="footer">
                <p>数据来源: 币安合约API | 每5分钟采集一次 | {stats?.coinCount || 0} 个币种参与计算</p>
            </footer>
        </div>
    )
}

export default App
