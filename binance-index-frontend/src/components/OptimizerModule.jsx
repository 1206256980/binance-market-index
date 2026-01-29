import React, { useState, useEffect } from 'react'
import axios from 'axios'

/**
 * 策略优化器模块 - 遍历所有参数组合找出最优策略
 */
function OptimizerModule() {
    // 输入参数 - 从 localStorage 加载缓存
    const [totalAmount, setTotalAmount] = useState(() => {
        const value = localStorage.getItem('opt_totalAmount');
        return value !== null ? parseFloat(value) : 1000;
    })
    const [days, setDays] = useState(() => {
        const value = localStorage.getItem('opt_days');
        return value !== null ? parseInt(value) : 30;
    })
    const [selectedHours, setSelectedHours] = useState(() => {
        const saved = localStorage.getItem('opt_selectedHours')
        return saved ? JSON.parse(saved) : [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23]
    })
    const [selectedHoldHours, setSelectedHoldHours] = useState(() => {
        const saved = localStorage.getItem('opt_selectedHoldHours')
        return saved ? JSON.parse(saved) : [24, 48, 72]
    })

    // 配置项
    const holdHourOptions = [1, 2, 4, 8, 12, 24, 48, 72, 96, 120, 168]

    // 状态
    const [loading, setLoading] = useState(false)
    const [useApi, setUseApi] = useState(() => {
        return localStorage.getItem('opt_useApi') === 'true';
    })
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)
    const [currentPage, setCurrentPage] = useState(1)
    const [sortField, setSortField] = useState('totalProfit') // 'totalProfit' or 'winRate'
    const [sortOrder, setSortOrder] = useState('desc')
    const [expandedRow, setExpandedRow] = useState(null)
    const pageSize = 20

    const toggleHour = (hour) => {
        if (selectedHours.includes(hour)) {
            setSelectedHours(selectedHours.filter(h => h !== hour))
        } else {
            setSelectedHours([...selectedHours, hour].sort((a, b) => a - b))
        }
    }

    const toggleHoldHour = (hour) => {
        if (selectedHoldHours.includes(hour)) {
            setSelectedHoldHours(selectedHoldHours.filter(h => h !== hour))
        } else {
            setSelectedHoldHours([...selectedHoldHours, hour].sort((a, b) => a - b))
        }
    }

    const selectAllHours = () => setSelectedHours(Array.from({ length: 24 }, (_, i) => i))
    const selectNoneHours = () => setSelectedHours([])
    const selectDefaultHours = () => setSelectedHours([0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22])

    const selectAllHoldHours = () => setSelectedHoldHours([...holdHourOptions])
    const selectNoneHoldHours = () => setSelectedHoldHours([])

    const runOptimize = async () => {
        if (selectedHours.length === 0) {
            setError('请至少选择一个入场时间')
            return
        }
        if (selectedHoldHours.length === 0) {
            setError('请至少选择一个持仓时间')
            return
        }

        setLoading(true)
        setError(null)
        setCurrentPage(1) // 重置页码

        try {
            const res = await axios.get('/api/index/backtest/optimize', {
                params: {
                    totalAmount,
                    days,
                    entryHours: selectedHours.join(','),
                    holdHours: selectedHoldHours.join(','),
                    useApi,
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

    const handleSort = (field) => {
        if (sortField === field) {
            setSortOrder(sortOrder === 'desc' ? 'asc' : 'desc')
        } else {
            setSortField(field)
            setSortOrder('desc')
        }
        setCurrentPage(1) // 排序后重置页码
        setExpandedRow(null) // 排序后收起所有行
    }

    const handleRowClick = (key) => {
        setExpandedRow(expandedRow === key ? null : key)
    }

    const getStrategyKey = (s) => `${s.rankingHours}-${s.topN}-${s.entryHour}-${s.holdHours}`

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

    // 排序和分页计算
    const sortedStrategies = result?.topStrategies ? [...result.topStrategies].sort((a, b) => {
        const factor = sortOrder === 'desc' ? -1 : 1
        return (a[sortField] - b[sortField]) * factor
    }) : []

    const paginatedStrategies = sortedStrategies.slice(
        (currentPage - 1) * pageSize,
        currentPage * pageSize
    )
    const totalPages = Math.ceil(sortedStrategies.length / pageSize)

    return (
        <div className="optimizer-module">
            <div className="optimizer-header">
                <div className="optimizer-title">🔍 策略优化器</div>
                <div className="optimizer-subtitle">自定义入场时间组合，寻找盈利最高的策略</div>
            </div>

            {/* 参数输入区 - 紧凑型横向布局 */}
            <div className="optimizer-params-compact">
                <div className="params-main-row">
                    <div className="param-item">
                        <label>总额(U)</label>
                        <input
                            type="number"
                            value={totalAmount}
                            onChange={(e) => setTotalAmount(e.target.value === '' ? '' : parseFloat(e.target.value))}
                            onBlur={(e) => { if (e.target.value === '' || isNaN(totalAmount)) setTotalAmount(1000) }}
                        />
                    </div>

                    <div className="param-item">
                        <label>回测天数</label>
                        <input
                            type="number"
                            value={days}
                            onChange={(e) => setDays(e.target.value === '' ? '' : parseInt(e.target.value))}
                            onBlur={(e) => { if (e.target.value === '' || isNaN(days)) setDays(30) }}
                        />
                    </div>

                    <div className="divider-v"></div>

                    <div className="hour-selection-compact">
                        <div className="label-with-actions">
                            <label>入场时间 ({selectedHours.length})</label>
                            <div className="quick-btns">
                                <button onClick={selectDefaultHours}>默认</button>
                                <button onClick={selectAllHours}>全选</button>
                                <button onClick={selectNoneHours}>全清</button>
                            </div>
                        </div>
                        <div className="hour-tags-container">
                            {Array.from({ length: 24 }, (_, i) => (
                                <span
                                    key={i}
                                    className={`hour-tag ${selectedHours.includes(i) ? 'active' : ''}`}
                                    onClick={() => toggleHour(i)}
                                >
                                    {i}
                                </span>
                            ))}
                        </div>
                    </div>

                    <div className="divider-v"></div>

                    <div className="hour-selection-compact">
                        <div className="label-with-actions">
                            <label>持仓时间 ({selectedHoldHours.length})</label>
                            <div className="quick-btns">
                                <button onClick={selectAllHoldHours}>全选</button>
                                <button onClick={selectNoneHoldHours}>全清</button>
                            </div>
                        </div>
                        <div className="hour-tags-container">
                            {holdHourOptions.map(h => (
                                <span
                                    key={h}
                                    className={`hour-tag ${selectedHoldHours.includes(h) ? 'active' : ''}`}
                                    onClick={() => toggleHoldHour(h)}
                                    style={{ minWidth: '32px' }}
                                >
                                    {h}
                                </span>
                            ))}
                        </div>
                    </div>

                    <div className="divider-v"></div>

                    <div className="api-toggle-compact">
                        <label className="checkbox-label">
                            <input
                                type="checkbox"
                                checked={useApi}
                                onChange={(e) => setUseApi(e.target.checked)}
                            />
                            <span>使用API</span>
                        </label>
                    </div>

                    <button
                        className={`optimize-run-btn ${loading ? 'loading' : ''}`}
                        onClick={runOptimize}
                        disabled={loading}
                    >
                        {loading ? '...' : '🚀 开始优化'}
                    </button>
                </div>
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
                    <div className="optimizer-result-header">
                        <div className="res-stats">
                            <span>✅ 测试组合: <strong>{result.totalCombinations}</strong></span>
                            <span>⏱️ 耗时: <strong>{(result.timeTakenMs / 1000).toFixed(1)}s</strong></span>
                        </div>
                        <div className="sort-hint">提示：点击“胜率”或“总盈亏”表头可切换排序</div>
                    </div>

                    <div className="optimizer-table-wrapper">
                        <table className="optimizer-table compact">
                            <thead>
                                <tr>
                                    <th>排名</th>
                                    <th>涨幅榜</th>
                                    <th>数量</th>
                                    <th>入场</th>
                                    <th>持仓</th>
                                    <th className="sortable-header" onClick={() => handleSort('winRate')}>
                                        单笔胜率 {sortField === 'winRate' && (sortOrder === 'desc' ? '▼' : '▲')}
                                    </th>
                                    <th className="sortable-header" onClick={() => handleSort('dailyWinRate')}>
                                        每日胜率 {sortField === 'dailyWinRate' && (sortOrder === 'desc' ? '▼' : '▲')}
                                    </th>
                                    <th className="sortable-header" onClick={() => handleSort('monthlyWinRate')}>
                                        每月胜率 {sortField === 'monthlyWinRate' && (sortOrder === 'desc' ? '▼' : '▲')}
                                    </th>
                                    <th>交易数</th>
                                    <th className="sortable-header" onClick={() => handleSort('totalProfit')}>
                                        总盈亏 {sortField === 'totalProfit' && (sortOrder === 'desc' ? '▼' : '▲')}
                                    </th>
                                </tr>
                            </thead>
                            <tbody>
                                {paginatedStrategies.map((strategy, idx) => {
                                    const rank = (currentPage - 1) * pageSize + idx + 1;
                                    const key = getStrategyKey(strategy);
                                    const isExpanded = expandedRow === key;

                                    return (
                                        <React.Fragment key={key}>
                                            <tr
                                                className={`${rank === 1 ? 'top-strategy' : ''} clickable-row ${isExpanded ? 'active-row' : ''}`}
                                                onClick={() => handleRowClick(key)}
                                            >
                                                <td className="rank-cell">#{rank}</td>
                                                <td>{formatRankingHours(strategy.rankingHours)}</td>
                                                <td>{strategy.topN}</td>
                                                <td>{strategy.entryHour}:00</td>
                                                <td>{strategy.holdHours}h</td>
                                                <td className={strategy.winRate >= 50 ? 'positive' : 'negative'}>
                                                    {strategy.winRate}%
                                                </td>
                                                <td className={strategy.dailyWinRate >= 50 ? 'positive' : 'negative'}>
                                                    {strategy.dailyWinRate}% ({strategy.winDays}/{strategy.winDays + strategy.loseDays})
                                                </td>
                                                <td className={strategy.monthlyWinRate >= 50 ? 'positive' : 'negative'}>
                                                    {strategy.monthlyWinRate}% ({strategy.winMonths}/{strategy.winMonths + strategy.loseMonths})
                                                </td>
                                                <td>{strategy.totalTrades}</td>
                                                <td className={getProfitClass(strategy.totalProfit)}>
                                                    {formatProfit(strategy.totalProfit)} U
                                                </td>
                                            </tr>
                                            {isExpanded && strategy.monthlyResults && (
                                                <tr className="expanded-details-row">
                                                    <td colSpan="10">
                                                        <div className="monthly-details-wrapper">
                                                            {strategy.monthlyResults.map((m, mIdx) => (
                                                                <div key={mIdx} className="monthly-detail-card">
                                                                    <div className="monthly-detail-header">
                                                                        <span>{m.monthLabel}</span>
                                                                        {m.totalProfit > 0 ? '🟢 盈利' : '🔴 亏损'}
                                                                    </div>
                                                                    <div className={`monthly-detail-profit ${m.totalProfit >= 0 ? 'positive' : 'negative'}`}>
                                                                        {m.totalProfit > 0 ? '+' : ''}{m.totalProfit} U
                                                                    </div>
                                                                    <div className="monthly-detail-days">
                                                                        📅 盈利 {m.winDays} 天 / 亏损 {m.loseDays} 天
                                                                    </div>
                                                                </div>
                                                            ))}
                                                        </div>
                                                    </td>
                                                </tr>
                                            )}
                                        </React.Fragment>
                                    );
                                })}
                            </tbody>
                        </table>

                        {/* 分页控制 */}
                        {totalPages > 1 && (
                            <div className="standard-pagination">
                                <button
                                    disabled={currentPage === 1}
                                    onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))}
                                >
                                    上一页
                                </button>
                                <div className="page-info">
                                    <strong>{currentPage}</strong> / {totalPages}
                                </div>
                                <button
                                    disabled={currentPage === totalPages}
                                    onClick={() => setCurrentPage(prev => Math.min(totalPages, prev + 1))}
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
}

export default OptimizerModule
