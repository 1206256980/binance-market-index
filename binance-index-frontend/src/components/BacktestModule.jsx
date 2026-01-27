import { useState } from 'react'
import axios from 'axios'

/**
 * 做空涨幅榜前10回测模块
 */
function BacktestModule() {
    // 输入参数
    const [entryHour, setEntryHour] = useState(12)
    const [entryMinute, setEntryMinute] = useState(0)
    const [amountPerCoin, setAmountPerCoin] = useState(100)
    const [days, setDays] = useState(30)

    // 状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)
    const [expandedDay, setExpandedDay] = useState(null)

    const runBacktest = async () => {
        setLoading(true)
        setError(null)

        try {
            const res = await axios.get('/api/index/backtest/short-top10', {
                params: {
                    entryHour,
                    entryMinute,
                    amountPerCoin,
                    days,
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
                <div className="backtest-title">📊 做空涨幅榜前10回测</div>
                <div className="backtest-subtitle">每天固定时间做空24小时涨幅前10的币种，24小时后平仓</div>
            </div>

            {/* 参数输入区 */}
            <div className="backtest-params">
                <div className="param-group">
                    <label>入场时间</label>
                    <div className="time-inputs">
                        <input
                            type="number"
                            min="0"
                            max="23"
                            value={entryHour}
                            onChange={(e) => setEntryHour(parseInt(e.target.value) || 0)}
                        />
                        <span>:</span>
                        <input
                            type="number"
                            min="0"
                            max="59"
                            value={entryMinute}
                            onChange={(e) => setEntryMinute(parseInt(e.target.value) || 0)}
                        />
                    </div>
                </div>

                <div className="param-group">
                    <label>每币金额 (U)</label>
                    <input
                        type="number"
                        min="1"
                        value={amountPerCoin}
                        onChange={(e) => setAmountPerCoin(parseFloat(e.target.value) || 100)}
                    />
                </div>

                <div className="param-group">
                    <label>回测天数</label>
                    <input
                        type="number"
                        min="1"
                        max="365"
                        value={days}
                        onChange={(e) => setDays(parseInt(e.target.value) || 30)}
                    />
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
                            <div className="summary-label">🎯 胜率</div>
                            <div className={`summary-value ${result.summary.winRate >= 50 ? 'positive' : 'negative'}`}>
                                {result.summary.winRate}%
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
                        {result.dailyResults.map((day, idx) => (
                            <div key={day.date} className="daily-item">
                                <div
                                    className={`daily-summary ${expandedDay === idx ? 'expanded' : ''}`}
                                    onClick={() => setExpandedDay(expandedDay === idx ? null : idx)}
                                >
                                    <span className="daily-date">{day.date}</span>
                                    <span className="daily-stats">
                                        盈利 <strong className="positive">{day.winCount}</strong> /
                                        亏损 <strong className="negative">{day.loseCount}</strong>
                                    </span>
                                    <span className={`daily-profit ${getProfitClass(day.totalProfit)}`}>
                                        {formatProfit(day.totalProfit)} U
                                    </span>
                                    <span className="expand-icon">{expandedDay === idx ? '▼' : '▶'}</span>
                                </div>

                                {expandedDay === idx && (
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
                                            <div key={tIdx} className="trade-row">
                                                <span className="trade-symbol">{trade.symbol.replace('USDT', '')}</span>
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
                        ))}
                    </div>
                </div>
            )}
        </div>
    )
}

export default BacktestModule
