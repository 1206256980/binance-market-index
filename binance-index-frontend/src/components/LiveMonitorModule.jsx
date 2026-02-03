import { useState, useEffect, memo } from 'react'
import axios from 'axios'

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

                    {/* 每小时明细 */}
                    <div className="daily-results">
                        <div className="daily-header">📋 每小时明细（点击展开）</div>
                        {result.hourlyResults.slice().reverse().map((hour, idx) => {
                            const isExpanded = expandedHours.includes(idx);
                            return (
                                <div key={hour.hour} className="daily-item">
                                    <div
                                        className={`daily-summary ${isExpanded ? 'expanded' : ''}`}
                                        onClick={() => {
                                            if (isExpanded) {
                                                setExpandedHours(expandedHours.filter(i => i !== idx));
                                            } else {
                                                setExpandedHours([...expandedHours, idx]);
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
    )
});

export default LiveMonitorModule
