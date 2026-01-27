import { useState } from 'react'
import axios from 'axios'

/**
 * 策略优化器模块 - 遍历所有参数组合找出最优策略
 */
function OptimizerModule() {
    // 输入参数
    const [totalAmount, setTotalAmount] = useState(1000)
    const [days, setDays] = useState(30)
    const [selectedHours, setSelectedHours] = useState([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23])

    // 状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)

    const toggleHour = (hour) => {
        if (selectedHours.includes(hour)) {
            setSelectedHours(selectedHours.filter(h => h !== hour))
        } else {
            setSelectedHours([...selectedHours, hour].sort((a, b) => a - b))
        }
    }

    const selectAllHours = () => setSelectedHours(Array.from({ length: 24 }, (_, i) => i))
    const selectNoneHours = () => setSelectedHours([])
    const selectDefaultHours = () => setSelectedHours([0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22])

    const runOptimize = async () => {
        if (selectedHours.length === 0) {
            setError('请至少选择一个入场时间')
            return
        }

        setLoading(true)
        setError(null)

        try {
            const res = await axios.get('/api/index/backtest/optimize', {
                params: {
                    totalAmount,
                    days,
                    entryHours: selectedHours.join(','),
                    timezone: 'Asia/Shanghai'
                }
            })

            if (res.data.success) {
                setResult(res.data)
            } else {
                setError(res.data.message || '优化失败')
            }
        } catch (err) {
            console.error('优化请求失败:', err)
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

    const formatRankingHours = (hours) => {
        if (hours === 168) return '7天'
        return `${hours}h`
    }

    return (
        <div className="optimizer-module">
            <div className="optimizer-header">
                <div className="optimizer-title">🔍 策略优化器</div>
                <div className="optimizer-subtitle">自定义入场时间组合，寻找盈利最高的策略</div>
            </div>

            {/* 参数输入区 */}
            <div className="optimizer-params">
                <div className="params-row">
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
                </div>

                <div className="hour-selection-wrapper">
                    <div className="hour-selection-header">
                        <label>入场时间选择 (多选)</label>
                        <div className="hour-quick-actions">
                            <button onClick={selectDefaultHours}>默认(2h)</button>
                            <button onClick={selectAllHours}>全选</button>
                            <button onClick={selectNoneHours}>全清</button>
                        </div>
                    </div>
                    <div className="hour-grid">
                        {Array.from({ length: 24 }, (_, i) => (
                            <div
                                key={i}
                                className={`hour-item ${selectedHours.includes(i) ? 'active' : ''}`}
                                onClick={() => toggleHour(i)}
                            >
                                {i}:00
                            </div>
                        ))}
                    </div>
                </div>

                <button
                    className={`optimizer-btn ${loading ? 'loading' : ''}`}
                    onClick={runOptimize}
                    disabled={loading}
                >
                    {loading ? '🔄 优化中...(根据组合数量耗时不等)' : '🚀 开始寻找最优策略'}
                </button>
            </div>

            {/* 错误提示 */}
            {error && (
                <div className="optimizer-error">
                    ⚠️ {error}
                </div>
            )}

            {/* 结果展示 */}
            {result && (
                <div className="optimizer-result">
                    {/* 统计信息 */}
                    <div className="optimizer-stats">
                        <span>✅ 共测试 <strong>{result.totalCombinations}</strong> 种组合</span>
                        <span>⏱️ 耗时 <strong>{(result.timeTakenMs / 1000).toFixed(1)}</strong> 秒</span>
                    </div>

                    {/* 最优策略表格 */}
                    <div className="optimizer-table-wrapper">
                        <div className="optimizer-table-title">🏆 最优策略 Top 10</div>
                        <table className="optimizer-table">
                            <thead>
                                <tr>
                                    <th>排名</th>
                                    <th>涨幅榜</th>
                                    <th>做空</th>
                                    <th>入场</th>
                                    <th>持仓</th>
                                    <th>胜率</th>
                                    <th>交易数</th>
                                    <th>总盈亏</th>
                                </tr>
                            </thead>
                            <tbody>
                                {result.topStrategies.map((strategy, idx) => (
                                    <tr key={idx} className={idx === 0 ? 'top-strategy' : ''}>
                                        <td className="rank">
                                            {idx === 0 ? '🥇' : idx === 1 ? '🥈' : idx === 2 ? '🥉' : idx + 1}
                                        </td>
                                        <td>{formatRankingHours(strategy.rankingHours)}</td>
                                        <td>前{strategy.topN}名</td>
                                        <td>{strategy.entryHour}:00</td>
                                        <td>{strategy.holdHours}h</td>
                                        <td className={strategy.winRate >= 50 ? 'positive' : 'negative'}>
                                            {strategy.winRate}%
                                        </td>
                                        <td>{strategy.totalTrades}</td>
                                        <td className={getProfitClass(strategy.totalProfit)}>
                                            {formatProfit(strategy.totalProfit)} U
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    )
}

export default OptimizerModule
