import { useState, useEffect, memo } from 'react'
import { createPortal } from 'react-dom'
import axios from 'axios'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'

/**
 * 实时持仓监控模块
 */
const LiveMonitorModule = memo(function LiveMonitorModule() {
    // 输入参数 - 从 localStorage 加载缓存
    const [rankingHours, setRankingHours] = useState(() => {
        const value = localStorage.getItem('lm_rankingHours');
        return value !== null ? parseInt(value) : 24;
    })
    const [topN, setTopN] = useState(() => {
        const value = localStorage.getItem('lm_topN');
        return value !== null ? parseInt(value) : 10;
    })
    const [hourlyAmount, setHourlyAmount] = useState(() => {
        const value = localStorage.getItem('lm_hourlyAmount');
        return value !== null ? parseFloat(value) : 1000;
    })
    const [monitorHours, setMonitorHours] = useState(() => {
        const value = localStorage.getItem('lm_monitorHours');
        return value !== null ? parseInt(value) : 24;
    })

    // 参数自动保存到 localStorage
    useEffect(() => {
        localStorage.setItem('lm_rankingHours', rankingHours)
        localStorage.setItem('lm_topN', topN)
        localStorage.setItem('lm_hourlyAmount', hourlyAmount)
        localStorage.setItem('lm_monitorHours', monitorHours)
    }, [rankingHours, topN, hourlyAmount, monitorHours])

    // 状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)
    const [expandedHours, setExpandedHours] = useState([])
    const [trackingData, setTrackingData] = useState(null) // 逐小时追踪数据
    const [expandedSnapshots, setExpandedSnapshots] = useState([]) // 逐小时追踪的展开状态

    // 侧边栏打开时锁定body滚动
    useEffect(() => {
        if (trackingData) {
            document.body.style.overflow = 'hidden'
        } else {
            document.body.style.overflow = ''
        }
        return () => {
            document.body.style.overflow = ''
        }
    }, [trackingData])

    const runMonitor = async () => {
        setLoading(true)
        setError(null)
        setExpandedHours([]) // 重置展开行

        try {
            const res = await axios.get('/api/index/live-monitor', {
                params: {
                    rankingHours,
                    topN,
                    hourlyAmount,
                    monitorHours,
                    timezone: 'Asia/Shanghai'
                }
            })

            if (res.data.success) {
                setResult(res.data)
            } else {
                setError(res.data.message || '监控失败')
            }
        } catch (err) {
            console.error('监控请求失败:', err)
            setError(err.response?.data?.message || err.message || '请求失败')
        } finally {
            setLoading(false)
        }
    }

    const formatProfit = (value) => {
        if (value === null || value === undefined) return '--'
        const prefix = value >= 0 ? '+' : ''
        return `${prefix}${value.toFixed(2)}`
    }

    const getProfitClass = (value) => {
        if (value === null || value === undefined) return ''
        return value >= 0 ? 'profit-positive' : 'profit-negative'
    }

    const formatHour = (hourStr) => {
        // 格式化小时显示，例如: 2026-02-03T12:00 -> 02-03 12:00
        try {
            const date = new Date(hourStr)
            const month = String(date.getMonth() + 1).padStart(2, '0')
            const day = String(date.getDate()).padStart(2, '0')
            const hour = String(date.getHours()).padStart(2, '0')
            const minute = String(date.getMinutes()).padStart(2, '0')
            return `${month}-${day} ${hour}:${minute}`
        } catch {
            return hourStr
        }
    }

    /**
     * ================================================================================
     * 逐小时盈亏追踪 (Hourly Profit/Loss Tracking)
     * ================================================================================
     * 
     * 【业务逻辑说明】
     * 这是一个"回顾性分析"功能，核心概念是：入场条件与追踪范围【解耦】
     * 
     * 1. hourData.entryTime（入场时间）的作用：
     *    - 仅用于确定"做空哪些币种"（该时刻涨幅榜 Top N）
     *    - 仅用于确定"入场价格是多少"（该时刻的开盘价）
     *    - 与追踪范围完全无关！
     * 
     * 2. 追踪范围由 monitorHours 和当前时间决定：
     *    - 开始时间 = 当前整点 - monitorHours
     *    - 结束时间 = 当前整点 + 最新5分钟K线
     * 
     * 【举例】
     * 假设当前时间 20:05，monitorHours=24：
     * - 用户点击"15:00"入场行的追踪按钮
     * - 系统获取 15:00 时刻的涨幅榜 Top N 作为做空标的
     * - 追踪范围 = 昨天 20:00 → 今天 20:00 + 最新价格
     * - 所有快照的盈亏都相对于 15:00 的入场价计算
     * 
     * 【设计意义】
     * 分析：如果使用某个时间点的入场条件，在过去N小时的市场行情下表现如何
     */
    const handleTrackingClick = async (hourData) => {
        try {
            const res = await axios.get('/api/index/live-monitor/hourly-tracking', {
                params: {
                    entryTime: hourData.entryTime,  // 用于确定做空币种和入场价格
                    rankingHours,                    // 涨幅榜周期（如24小时涨幅榜）
                    topN,                            // 做空前N名
                    totalAmount: hourlyAmount,       // 总投入金额
                    monitorHours,                    // 追踪范围长度（决定看过去多少小时）
                    timezone: 'Asia/Shanghai'
                }
            })

            if (res.data.success) {
                setTrackingData(res.data.data)
            } else {
                console.error('追踪失败:', res.data.message)
            }
        } catch (err) {
            console.error('追踪请求失败:', err)
        }
    }



    return (
        <div className="backtest-module">
            <div className="backtest-header">
                <div className="backtest-title">📊 实时持仓监控</div>
                <div className="backtest-subtitle">监控每个整点小时做空涨幅榜的实时盈亏情况</div>
            </div>

            {/* 参数输入区 */}
            <div className="backtest-params">
                <div className="param-group">
                    <label>涨幅榜周期</label>
                    <select
                        value={rankingHours}
                        onChange={(e) => setRankingHours(parseInt(e.target.value))}
                        className="ranking-select"
                    >
                        <option value={24}>24小时涨幅榜</option>
                        <option value={48}>48小时涨幅榜</option>
                        <option value={72}>72小时涨幅榜</option>
                        <option value={168}>7天涨幅榜</option>
                    </select>
                </div>

                <div className="param-group">
                    <label>做空前 N 名</label>
                    <select
                        value={topN}
                        onChange={(e) => setTopN(parseInt(e.target.value))}
                        className="ranking-select"
                    >
                        <option value={5}>前 5 名</option>
                        <option value={10}>前 10 名</option>
                        <option value={15}>前 15 名</option>
                        <option value={20}>前 20 名</option>
                        <option value={30}>前 30 名</option>
                    </select>
                </div>

                <div className="param-group">
                    <label>每小时总金额 (U)</label>
                    <input
                        type="number"
                        min="1"
                        value={hourlyAmount}
                        onChange={(e) => setHourlyAmount(e.target.value === '' ? '' : parseFloat(e.target.value))}
                        onBlur={(e) => { if (e.target.value === '' || isNaN(hourlyAmount)) setHourlyAmount(1000) }}
                    />
                </div>

                <div className="param-group">
                    <label>实时小时</label>
                    <select
                        value={monitorHours}
                        onChange={(e) => setMonitorHours(parseInt(e.target.value))}
                        className="ranking-select"
                    >
                        <option value={24}>24小时</option>
                        <option value={48}>48小时</option>
                        <option value={72}>72小时</option>
                        <option value={168}>7天(168小时)</option>
                    </select>
                </div>

                <button
                    className={`backtest-btn ${loading ? 'loading' : ''}`}
                    onClick={runMonitor}
                    disabled={loading}
                >
                    🚀 {loading ? '监控中...' : '开始监控'}
                </button>
            </div>

            {/* 错误提示 */}
            {error && (
                <div className="backtest-error">
                    ❌ {error}
                </div>
            )}

            {/* 结果展示 */}
            {result && (
                <div className="backtest-results">
                    {/* 汇总卡片 */}
                    <div className="result-summary">
                        <div className="summary-card">
                            <div className="summary-label">📅 监控小时</div>
                            <div className="summary-value">{result.summary.totalHours} 小时</div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">📝 总交易</div>
                            <div className="summary-value">{result.summary.totalTrades} 笔</div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">🎯 单笔胜率</div>
                            <div className="summary-value positive">{result.summary.winRate.toFixed(2)}%</div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">📊 每日胜率</div>
                            <div className="summary-value positive">
                                {result.summary.totalHours > 0
                                    ? ((result.hourlyResults.filter(h => h.totalProfit > 0).length / result.summary.totalHours) * 100).toFixed(0)
                                    : 0}% ({result.hourlyResults.filter(h => h.totalProfit > 0).length}/{result.summary.totalHours})
                            </div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">✅ 盈利笔数</div>
                            <div className="summary-value positive">{result.summary.winTrades}</div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">❌ 亏损笔数</div>
                            <div className="summary-value negative">{result.summary.loseTrades}</div>
                        </div>
                        <div className="summary-card highlight">
                            <div className="summary-label">💰 总盈亏</div>
                            <div className={`summary-value large ${getProfitClass(result.summary.totalProfit)}`}>
                                {formatProfit(result.summary.totalProfit)} U
                            </div>
                        </div>
                    </div>

                    {/* 出场时间提示 */}
                    {result.exitTime && (
                        <div className="exit-time-info">
                            ⏰ 当前出场时间（对齐5分钟）: <strong>{formatHour(result.exitTime)}</strong>
                        </div>
                    )}

                    {/* 盈亏折线图 */}
                    <div className="profit-chart-container">
                        <div className="chart-title">📈 盈亏趋势图</div>
                        <ResponsiveContainer width="100%" height={280}>
                            <LineChart
                                data={result.hourlyResults.map(hour => ({
                                    time: formatHour(hour.hour),
                                    profit: parseFloat(hour.totalProfit.toFixed(2)),
                                    fullTime: hour.hour
                                }))}
                                margin={{ top: 10, right: 30, left: 10, bottom: 5 }}
                            >
                                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                                <XAxis
                                    dataKey="time"
                                    tick={{ fontSize: 11, fill: '#64748b' }}
                                    angle={-45}
                                    textAnchor="end"
                                    height={60}
                                />
                                <YAxis
                                    tick={{ fontSize: 12, fill: '#64748b' }}
                                    label={{ value: '盈亏 (U)', angle: -90, position: 'insideLeft', style: { fontSize: 12, fill: '#64748b' } }}
                                />
                                <Tooltip
                                    contentStyle={{
                                        backgroundColor: 'rgba(255, 255, 255, 0.95)',
                                        border: '1px solid #e2e8f0',
                                        borderRadius: '8px',
                                        fontSize: '13px',
                                        boxShadow: '0 4px 12px rgba(0, 0, 0, 0.1)'
                                    }}
                                    formatter={(value) => [`${value >= 0 ? '+' : ''}${value} U`, '盈亏']}
                                />
                                <ReferenceLine y={0} stroke="#94a3b8" strokeDasharray="3 3" />
                                <Line
                                    type="monotone"
                                    dataKey="profit"
                                    stroke="url(#profitGradient)"
                                    strokeWidth={3}
                                    dot={{ fill: '#667eea', r: 4 }}
                                    activeDot={{ r: 6 }}
                                />
                                <defs>
                                    <linearGradient id="profitGradient" x1="0" y1="0" x2="1" y2="0">
                                        <stop offset="0%" stopColor="#667eea" />
                                        <stop offset="100%" stopColor="#764ba2" />
                                    </linearGradient>
                                </defs>
                            </LineChart>
                        </ResponsiveContainer>
                    </div>

                    {/* 每小时明细 */}
                    <div className="daily-results">
                        <div className="daily-header">📋 每小时明细（点击展开）</div>
                        {result.hourlyResults.slice().reverse().map((hour, idx) => {
                            const isExpanded = expandedHours.includes(idx);
                            return (
                                <div key={hour.hour} className="daily-item">
                                    <div
                                        className={`daily-summary ${isExpanded ? 'expanded' : ''}`}
                                        onClick={(e) => {
                                            // 防止追踪按钮点击触发展开
                                            if (!e.target.closest('.tracking-btn')) {
                                                if (isExpanded) {
                                                    setExpandedHours(expandedHours.filter(i => i !== idx));
                                                } else {
                                                    setExpandedHours([...expandedHours, idx]);
                                                }
                                            }
                                        }}
                                    >
                                        <span className="daily-date">
                                            {formatHour(hour.hour)}
                                        </span>
                                        <span className="daily-stats">
                                            盈利 <strong className="positive">{hour.winCount}</strong> /
                                            亏损 <strong className="negative">{hour.loseCount}</strong>
                                        </span>
                                        <span className={`daily-profit ${getProfitClass(hour.totalProfit)}`}>
                                            {formatProfit(hour.totalProfit)} U
                                        </span>
                                        <button
                                            className="tracking-btn modern-btn"
                                            onClick={() => {
                                                handleTrackingClick({ entryTime: hour.hour })
                                                setExpandedSnapshots([]) // 打开追踪时重置折叠状态
                                            }}
                                            title="查看逐小时追踪"
                                        >
                                            <span className="btn-icon">📈</span>
                                            <span className="btn-text">追踪</span>
                                        </button>
                                        <span className="expand-icon">{isExpanded ? '▼' : '▶'}</span>
                                    </div>

                                    {isExpanded && (
                                        <div className="daily-trades">
                                            <div className="trade-header">
                                                <span>币种</span>
                                                <span>入场涨幅</span>
                                                <span>开仓价</span>
                                                <span>平仓价</span>
                                                <span>盈亏%</span>
                                                <span>盈亏U</span>
                                            </div>
                                            {hour.trades.map((trade, tIdx) => (
                                                <div key={tIdx} className="trade-row">
                                                    <span className="trade-symbol">
                                                        {trade.symbol.replace('USDT', '')}
                                                    </span>
                                                    <span className="trade-change positive">
                                                        +{trade.change24h?.toFixed(2)}%
                                                    </span>
                                                    <span>{trade.entryPrice}</span>
                                                    <span>{trade.exitPrice}</span>
                                                    <span className={getProfitClass(trade.profitPercent)}>
                                                        {formatProfit(trade.profitPercent)}%
                                                    </span>
                                                    <span className={getProfitClass(trade.profit)}>
                                                        {formatProfit(trade.profit)}
                                                    </span>
                                                </div>
                                            ))}                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </div>
            )}

            {/* 逐小时追踪侧边栏 - Portal到body */}
            {createPortal(
                <>
                    {trackingData && (
                        <div className="sidebar-overlay" onClick={() => setTrackingData(null)} />
                    )}
                    <div className={`sidebar-container ${trackingData ? 'open' : ''}`} onClick={e => e.stopPropagation()}>
                        {trackingData && (
                            <div className="sidebar-content-wrapper">
                                <div className="sidebar-header">
                                    <div className="sidebar-title">
                                        <span>📈 {trackingData.entryTime} 逐小时追踪</span>
                                        <span className="sidebar-subtitle">
                                            {trackingData.strategy.rankingHours}h | Top {trackingData.strategy.topN}
                                        </span>
                                    </div>
                                    <button className="modal-close" onClick={() => setTrackingData(null)}>✕</button>
                                </div>
                                <div className="sidebar-body">
                                    {/* 价格指数趋势图 - 显示做空币种的综合价格走势 */}
                                    <div className="sidebar-chart-container">
                                        <ResponsiveContainer width="100%" height={220}>
                                            {(() => {
                                                // 准备图表数据 - 使用后端返回的专门用于图表的完整24小时数据
                                                const chartData = (trackingData.priceIndexData || []).map(point => ({
                                                    time: point.time.split(' ')[1], // 只显示时间部分
                                                    priceIndex: point.priceIndex ? parseFloat(point.priceIndex.toFixed(2)) : 100,
                                                    isPivot: point.isPivot,
                                                    isLatest: point.isLatest
                                                }));

                                                return (
                                                    <LineChart data={chartData} margin={{ top: 10, right: 20, left: 0, bottom: 5 }}>
                                                        <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                                                        <XAxis
                                                            dataKey="time"
                                                            tick={{ fontSize: 10, fill: '#64748b' }}
                                                            height={40}
                                                            interval="preserveStartEnd"
                                                        />
                                                        <YAxis
                                                            tick={{ fontSize: 11, fill: '#64748b' }}
                                                            width={50}
                                                            domain={['dataMin - 2', 'dataMax + 2']}
                                                        />
                                                        <Tooltip
                                                            contentStyle={{
                                                                backgroundColor: 'rgba(255, 255, 255, 0.95)',
                                                                border: '1px solid #e2e8f0',
                                                                borderRadius: '8px',
                                                                fontSize: '12px',
                                                                boxShadow: '0 4px 12px rgba(0, 0, 0, 0.1)'
                                                            }}
                                                            formatter={(value) => [value.toFixed(2), '价格指数']}
                                                            labelFormatter={(label) => `时间: ${label}`}
                                                        />
                                                        {/* 基准线 y=100（入场价格） */}
                                                        <ReferenceLine
                                                            y={100}
                                                            stroke="#667eea"
                                                            strokeWidth={2}
                                                            strokeDasharray="5 5"
                                                            label={{
                                                                value: '入场价(100)',
                                                                position: 'right',
                                                                fill: '#667eea',
                                                                fontSize: 10
                                                            }}
                                                        />
                                                        <Line
                                                            type="monotone"
                                                            dataKey="priceIndex"
                                                            stroke="url(#priceIndexGradient)"
                                                            strokeWidth={2.5}
                                                            dot={(props) => {
                                                                const { cx, cy, payload } = props;
                                                                if (payload.isPivot) {
                                                                    // 基准点用大红点+外圈标记
                                                                    return (
                                                                        <g key={`pivot-${payload.time}`}>
                                                                            <circle cx={cx} cy={cy} r={8} fill="#ef4444" opacity={0.3} />
                                                                            <circle cx={cx} cy={cy} r={5} fill="#ef4444" stroke="#fff" strokeWidth={2} />
                                                                            <text x={cx} y={cy - 12} textAnchor="middle" fill="#ef4444" fontSize={10} fontWeight="bold">入场</text>
                                                                        </g>
                                                                    );
                                                                }
                                                                if (payload.isLatest) {
                                                                    // 实时点用绿点标记
                                                                    return (
                                                                        <g key={`latest-${payload.time}`}>
                                                                            <circle cx={cx} cy={cy} r={5} fill="#22c55e" stroke="#fff" strokeWidth={2} />
                                                                        </g>
                                                                    );
                                                                }
                                                                // 普通点
                                                                return <circle key={`dot-${payload.time}`} cx={cx} cy={cy} r={3} fill="#667eea" />;
                                                            }}
                                                            activeDot={{ r: 6 }}
                                                        />
                                                        <defs>
                                                            <linearGradient id="priceIndexGradient" x1="0" y1="0" x2="1" y2="0">
                                                                <stop offset="0%" stopColor="#667eea" />
                                                                <stop offset="100%" stopColor="#764ba2" />
                                                            </linearGradient>
                                                        </defs>
                                                    </LineChart>
                                                );
                                            })()}
                                        </ResponsiveContainer>
                                        <div style={{ textAlign: 'center', fontSize: '11px', color: '#64748b', marginTop: '4px' }}>
                                            📈 指数&gt;100 = 币价上涨(亏损方向) | 📉 指数&lt;100 = 币价下跌(盈利方向)
                                        </div>
                                    </div>





                                    {/* 快照卡片列表 - 倒序显示，最新在上面 */}
                                    {trackingData.hourlySnapshots.slice().reverse().map((snapshot, idx) => {
                                        const originalIdx = trackingData.hourlySnapshots.length - 1 - idx;
                                        const isSnapshotExpanded = expandedSnapshots.includes(originalIdx);
                                        return (
                                            <div key={idx} className="hourly-snapshot-card">
                                                <div
                                                    className={`snapshot-header clickable ${isSnapshotExpanded ? 'expanded' : ''}`}
                                                    onClick={() => {
                                                        if (isSnapshotExpanded) {
                                                            setExpandedSnapshots(expandedSnapshots.filter(i => i !== originalIdx));
                                                        } else {
                                                            setExpandedSnapshots([...expandedSnapshots, originalIdx]);
                                                        }
                                                    }}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    <span className="time">
                                                        {snapshot.snapshotTime}
                                                        {snapshot.isPivot && <span className="pivot-badge">基准点</span>}
                                                        {snapshot.isLatest && <span className="latest-badge">实时</span>}
                                                    </span>
                                                    <span className="duration">
                                                        {snapshot.hoursFromPivot === 0 ? '基准点' :
                                                            snapshot.hoursFromPivot > 0 ? `+${snapshot.hoursFromPivot}h` :
                                                                `${snapshot.hoursFromPivot}h`}
                                                    </span>
                                                    <span className={`profit ${snapshot.totalProfit >= 0 ? 'positive' : 'negative'}`}>
                                                        {snapshot.totalProfit >= 0 ? '+' : ''}{snapshot.totalProfit.toFixed(2)} U
                                                    </span>
                                                    <span className="expand-icon-small">{isSnapshotExpanded ? '▼' : '▶'}</span>
                                                </div>
                                                {isSnapshotExpanded && (
                                                    <div className="daily-trades">
                                                        <div className="trade-header">
                                                            <span>币种</span>
                                                            <span>入场涨幅</span>
                                                            <span>开仓价</span>
                                                            <span>平仓价</span>
                                                            <span>盈亏%</span>
                                                            <span>盈亏U</span>
                                                        </div>
                                                        {snapshot.trades.map((trade, tIdx) => (
                                                            <div key={tIdx} className="trade-row">
                                                                <span className="trade-symbol">{trade.symbol.replace('USDT', '')}</span>
                                                                <span className="trade-change" style={{ color: 'var(--success)' }}>+{trade.change24h.toFixed(2)}%</span>
                                                                <span>{trade.entryPrice < 1 ? trade.entryPrice.toFixed(6) : trade.entryPrice.toFixed(4)}</span>
                                                                <span>{trade.exitPrice < 1 ? trade.exitPrice.toFixed(6) : trade.exitPrice.toFixed(4)}</span>
                                                                <span className={trade.profitPercent >= 0 ? 'p-up' : 'p-down'}>
                                                                    {trade.profitPercent > 0 ? '+' : ''}{trade.profitPercent.toFixed(2)}%
                                                                </span>
                                                                <span className={trade.profit >= 0 ? 'p-up' : 'p-down'}>
                                                                    {trade.profit > 0 ? '+' : ''}{trade.profit.toFixed(2)}
                                                                </span>
                                                            </div>
                                                        ))}
                                                    </div>
                                                )}
                                            </div>
                                        );
                                    })}
                                </div>
                            </div>
                        )}
                    </div>
                </>,
                document.body
            )}
        </div>
    )
});

export default LiveMonitorModule
