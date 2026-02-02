import { useState, useEffect, memo } from 'react'
import axios from 'axios'

/**
 * 做空涨幅榜前10回测模块
 */
const BacktestModule = memo(function BacktestModule() {
    // 输入参数 - 从 localStorage 加载缓存
    const [entryHour, setEntryHour] = useState(() => {
        const value = localStorage.getItem('bt_entryHour');
        return value !== null ? parseInt(value) : 12;
    })
    const [entryMinute, setEntryMinute] = useState(() => {
        const value = localStorage.getItem('bt_entryMinute');
        return value !== null ? parseInt(value) : 0;
    })
    const [totalAmount, setTotalAmount] = useState(() => {
        const value = localStorage.getItem('bt_totalAmount');
        return value !== null ? parseFloat(value) : 1000;
    })
    const [days, setDays] = useState(() => {
        const value = localStorage.getItem('bt_days');
        return value !== null ? parseInt(value) : 30;
    })
    const [rankingHours, setRankingHours] = useState(() => {
        const value = localStorage.getItem('bt_rankingHours');
        return value !== null ? parseInt(value) : 24;
    })
    const [holdHours, setHoldHours] = useState(() => {
        const value = localStorage.getItem('bt_holdHours');
        return value !== null ? parseInt(value) : 24;
    })
    const [topN, setTopN] = useState(() => {
        const value = localStorage.getItem('bt_topN');
        return value !== null ? parseInt(value) : 10;
    })

    // 参数自动保存到 localStorage
    useEffect(() => {
        localStorage.setItem('bt_entryHour', entryHour)
        localStorage.setItem('bt_entryMinute', entryMinute)
        localStorage.setItem('bt_totalAmount', totalAmount)
        localStorage.setItem('bt_days', days)
        localStorage.setItem('bt_rankingHours', rankingHours)
        localStorage.setItem('bt_holdHours', holdHours)
        localStorage.setItem('bt_topN', topN)
    }, [entryHour, entryMinute, totalAmount, days, rankingHours, holdHours, topN])

    // 状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)
    const [expandedDays, setExpandedDays] = useState([])
    const [currentPage, setCurrentPage] = useState(1)
    const pageSize = 30

    const runBacktest = async () => {
        setLoading(true)
        setError(null)
        setExpandedDays([]) // 重置展开行

        try {
            const res = await axios.get('/api/index/backtest/short-top10', {
                params: {
                    entryHour,
                    entryMinute,
                    totalAmount,
                    days,
                    rankingHours,
                    holdHours,
                    topN,
                    useApi: true,
                    timezone: 'Asia/Shanghai'
                }
            })

            if (res.data.success) {
                setResult(res.data)
            } else {
                setError(res.data.message || '回测失败')
            }
        } catch (err) {
            console.error('回测请求失败:', err)
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

    return (
        <div className="backtest-module">
            <div className="backtest-header">
                <div className="backtest-title">📊 做空涨幅榜回测</div>
                <div className="backtest-subtitle">每天固定时间做空涨幅榜的币种，按选定时间平仓</div>
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
                    <label>入场时间</label>
                    <div className="time-inputs">
                        <input
                            type="number"
                            min="0"
                            max="23"
                            value={entryHour}
                            onChange={(e) => setEntryHour(e.target.value === '' ? '' : parseInt(e.target.value))}
                            onBlur={(e) => { if (e.target.value === '' || isNaN(entryHour)) setEntryHour(0) }}
                        />
                        <span>:</span>
                        <input
                            type="number"
                            min="0"
                            max="59"
                            value={entryMinute}
                            onChange={(e) => setEntryMinute(e.target.value === '' ? '' : parseInt(e.target.value))}
                            onBlur={(e) => { if (e.target.value === '' || isNaN(entryMinute)) setEntryMinute(0) }}
                        />
                    </div>
                </div>

                <div className="param-group">
                    <label>每日总金额 (U)</label>
                    <input
                        type="number"
                        min="1"
                        value={totalAmount}
                        onChange={(e) => setTotalAmount(e.target.value === '' ? '' : parseFloat(e.target.value))}
                        onBlur={(e) => { if (e.target.value === '' || isNaN(totalAmount)) setTotalAmount(1000) }}
                    />
                </div>

                <div className="param-group">
                    <label>回测天数</label>
                    <input
                        type="number"
                        min="1"
                        max="365"
                        value={days}
                        onChange={(e) => setDays(e.target.value === '' ? '' : parseInt(e.target.value))}
                        onBlur={(e) => { if (e.target.value === '' || isNaN(days)) setDays(30) }}
                    />
                </div>

                <div className="param-group">
                    <label>持仓时间</label>
                    <select
                        value={holdHours}
                        onChange={(e) => setHoldHours(parseInt(e.target.value))}
                        className="ranking-select"
                    >
                        <option value={24}>24小时</option>
                        <option value={48}>48小时</option>
                        <option value={72}>72小时</option>
                    </select>
                </div>

                <button
                    className={`backtest-btn ${loading ? 'loading' : ''}`}
                    onClick={runBacktest}
                    disabled={loading}
                >
                    {loading ? '🔄 回测中...' : '🚀 开始回测'}
                </button>
            </div>

            {/* 错误提示 */}
            {error && (
                <div className="backtest-error">
                    ⚠️ {error}
                </div>
            )}

            {/* 结果展示 */}
            {result && (
                <div className="backtest-result">
                    {/* 总体统计 */}
                    <div className="result-summary">
                        <div className="summary-card">
                            <div className="summary-label">📅 有效天数</div>
                            <div className="summary-value">{result.summary.validDays} / {result.summary.totalDays}</div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">📈 总交易</div>
                            <div className="summary-value">{result.summary.totalTrades} 笔</div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">🎯 单笔胜率</div>
                            <div className={`summary-value ${result.summary.winRate >= 50 ? 'positive' : 'negative'}`}>
                                {result.summary.winRate}%
                            </div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">📊 每日胜率</div>
                            <div className={`summary-value ${result.summary.dailyWinRate >= 50 ? 'positive' : 'negative'}`}>
                                {result.summary.dailyWinRate}% ({result.summary.winDays}/{result.summary.winDays + result.summary.loseDays})
                            </div>
                        </div>
                        <div className="summary-card">
                            <div className="summary-label">📆 每月胜率</div>
                            <div className={`summary-value ${result.summary.monthlyWinRate >= 50 ? 'positive' : 'negative'}`}>
                                {result.summary.monthlyWinRate}% ({result.summary.winMonths}/{result.summary.winMonths + result.summary.loseMonths})
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

                    {/* 跳过的日期提示 */}
                    {result.skippedDays && result.skippedDays.length > 0 && (
                        <div className="skipped-days">
                            ⚠️ 以下日期因数据缺失被跳过: {result.skippedDays.join(', ')}
                        </div>
                    )}

                    {/* 每日明细 */}
                    <div className="daily-results">
                        <div className="daily-header">📋 每日明细（点击展开）</div>
                        {result.dailyResults.slice().reverse().slice((currentPage - 1) * pageSize, currentPage * pageSize).map((day, idx) => {
                            const globalIdx = (currentPage - 1) * pageSize + idx;
                            const isExpanded = expandedDays.includes(globalIdx);
                            return (
                                <div key={day.date} className="daily-item">
                                    <div
                                        className={`daily-summary ${isExpanded ? 'expanded' : ''} ${day.isLive ? 'is-live' : ''}`}
                                        onClick={() => {
                                            if (isExpanded) {
                                                setExpandedDays(expandedDays.filter(i => i !== globalIdx));
                                            } else {
                                                setExpandedDays([...expandedDays, globalIdx]);
                                            }
                                        }}
                                    >
                                        <span className="daily-date">
                                            {day.date}
                                            {day.isLive && <span className="live-badge">进行中 (实时)</span>}
                                        </span>
                                        <span className="daily-stats">
                                            盈利 <strong className="positive">{day.winCount}</strong> /
                                            亏损 <strong className="negative">{day.loseCount}</strong>
                                        </span>
                                        <span className={`daily-profit ${getProfitClass(day.totalProfit)}`}>
                                            {formatProfit(day.totalProfit)} U
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
                                            {day.trades.map((trade, tIdx) => (
                                                <div key={tIdx} className={`trade-row ${trade.isLive ? 'is-live' : ''}`}>
                                                    <span className="trade-symbol">
                                                        {trade.symbol.replace('USDT', '')}
                                                        {trade.isLive && <span className="live-badge">LIVE</span>}
                                                    </span>
                                                    <span className="trade-change positive">+{trade.change24h?.toFixed(2)}%</span>
                                                    <span>{trade.entryPrice?.toFixed(4)}</span>
                                                    <span>{trade.exitPrice?.toFixed(4)}</span>
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

                        {/* 分页控制 */}
                        {result.dailyResults.length > pageSize && (
                            <div className="standard-pagination">
                                <button
                                    disabled={currentPage === 1}
                                    onClick={() => {
                                        setCurrentPage(prev => Math.max(1, prev - 1));
                                        setExpandedDays([]);
                                    }}
                                >
                                    上一页
                                </button>
                                <div className="page-info">
                                    <strong>{currentPage}</strong> / {Math.ceil(result.dailyResults.length / pageSize)}
                                </div>
                                <button
                                    disabled={currentPage === Math.ceil(result.dailyResults.length / pageSize)}
                                    onClick={() => {
                                        setCurrentPage(prev => Math.min(Math.ceil(result.dailyResults.length / pageSize), prev + 1));
                                        setExpandedDays([]);
                                    }}
                                >
                                    下一页
                                </button>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    )
});

export default BacktestModule
