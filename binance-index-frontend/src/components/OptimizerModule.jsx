import React, { useState, useEffect, memo } from 'react'
import { createPortal } from 'react-dom'

import axios from 'axios'

/**
 * 策略优化器模块 - 遍历所有参数组合找出最优策略
 */
const OptimizerModule = memo(function OptimizerModule() {
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
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)
    const [currentPage, setCurrentPage] = useState(1)
    const [sortField, setSortField] = useState('totalProfit') // 'totalProfit' or 'winRate'
    const [sortOrder, setSortOrder] = useState('desc')
    const [expandedRows, setExpandedRows] = useState([])
    const [selectedModal, setSelectedModal] = useState(null) // { strategy, monthLabel }
    const [detailPage, setDetailPage] = useState(1)
    const [expandedDate, setExpandedDate] = useState(null)
    const pageSize = 20
    const detailPageSize = 20

    // 持久化输入参数
    useEffect(() => {
        localStorage.setItem('opt_totalAmount', totalAmount.toString());
        localStorage.setItem('opt_days', days.toString());
        localStorage.setItem('opt_selectedHours', JSON.stringify(selectedHours));
        localStorage.setItem('opt_selectedHoldHours', JSON.stringify(selectedHoldHours));
    }, [totalAmount, days, selectedHours, selectedHoldHours])

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
        setExpandedRows([]) // 重置展开行

        try {
            const res = await axios.get('/api/index/backtest/optimize', {
                params: {
                    totalAmount,
                    days,
                    entryHours: selectedHours.join(','),
                    holdHours: selectedHoldHours.join(','),
                    useApi: true,
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
        setExpandedRows([]) // 排序后收起所有行
        setSelectedModal(null)
    }

    const handleRowClick = (key) => {
        if (expandedRows.includes(key)) {
            setExpandedRows(expandedRows.filter(k => k !== key))
            if (activeDetail?.strategyKey === key) setActiveDetail(null)
        } else {
            setExpandedRows([...expandedRows, key])
        }
    }

    const toggleDailyDetail = (strategy, monthLabel, e) => {
        e.stopPropagation(); // 防止触发行展开/收起
        setSelectedModal({ strategy, monthLabel });
        setDetailPage(1);
        setExpandedDate(null);
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

        let valA, valB;
        if (sortField.startsWith('month:')) {
            const mLabel = sortField.split(':')[1];
            const resA = a.monthlyResults?.find(m => m.monthLabel === mLabel);
            const resB = b.monthlyResults?.find(m => m.monthLabel === mLabel);
            valA = resA ? resA.totalProfit : -999999;
            valB = resB ? resB.totalProfit : -999999;
        } else {
            valA = a[sortField] || 0;
            valB = b[sortField] || 0;
        }

        return (valA - valB) * factor
    }) : []

    const paginatedStrategies = sortedStrategies.slice(
        (currentPage - 1) * pageSize,
        currentPage * pageSize
    )
    const totalPages = Math.ceil(sortedStrategies.length / pageSize)

    // 提取所有出现的月份，并按时间排序
    const allMonths = Array.from(new Set(
        result?.topStrategies?.flatMap(s => s.monthlyResults?.map(m => m.monthLabel) || [])
    )).sort();

    // 计算每个月各策略的排名
    const monthlyRankings = {}; // { "2024-09": { "strategyKey": rank } }
    if (result?.topStrategies) {
        allMonths.forEach(month => {
            const sortedForMonth = [...result.topStrategies]
                .map(s => {
                    const monthResult = s.monthlyResults?.find(m => m.monthLabel === month);
                    return { key: getStrategyKey(s), profit: monthResult ? monthResult.totalProfit : -999999 };
                })
                .sort((a, b) => b.profit - a.profit);

            monthlyRankings[month] = {};
            sortedForMonth.forEach((s, idx) => {
                monthlyRankings[month][s.key] = idx + 1;
            });
        });
    }

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
                                    <th>策略配置 (榜/数/入/持)</th>
                                    {allMonths.map(month => (
                                        <th
                                            key={month}
                                            className="sortable-header"
                                            onClick={() => handleSort(`month:${month}`)}
                                        >
                                            {month.split('-')[1]}月收益 {sortField === `month:${month}` && (sortOrder === 'desc' ? '▼' : '▲')}
                                        </th>
                                    ))}
                                    <th className="sortable-header" onClick={() => handleSort('winRate')}>
                                        胜率 {sortField === 'winRate' && (sortOrder === 'desc' ? '▼' : '▲')}
                                    </th>
                                    <th className="sortable-header" onClick={() => handleSort('dailyWinRate')}>
                                        日胜率 {sortField === 'dailyWinRate' && (sortOrder === 'desc' ? '▼' : '▲')}
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
                                    const isExpanded = expandedRows.includes(key);

                                    return (
                                        <React.Fragment key={key}>
                                            <tr
                                                className={`${rank === 1 ? 'top-strategy' : ''} clickable-row ${isExpanded ? 'active-row' : ''}`}
                                                onClick={() => handleRowClick(key)}
                                            >
                                                <td className="rank-cell">#{rank}</td>
                                                <td>
                                                    <span className="strategy-config-tag">{formatRankingHours(strategy.rankingHours)}</span>
                                                    <span className="strategy-config-tag">{strategy.topN}币</span>
                                                    <span className="strategy-config-tag">{strategy.entryHour}:00</span>
                                                    <span className="strategy-config-tag">{strategy.holdHours}h</span>
                                                </td>
                                                {allMonths.map(month => {
                                                    const mRes = strategy.monthlyResults?.find(m => m.monthLabel === month);
                                                    const mRank = monthlyRankings[month][key];
                                                    return (
                                                        <td key={month} className="monthly-profit-cell">
                                                            <div className={getProfitClass(mRes?.totalProfit)}>
                                                                {mRes ? formatProfit(mRes.totalProfit) : '--'}
                                                            </div>
                                                            <div className="monthly-rank">#{mRank}</div>
                                                        </td>
                                                    );
                                                })}
                                                <td className={strategy.winRate >= 50 ? 'positive' : 'negative'}>
                                                    {strategy.winRate}%
                                                </td>
                                                <td className={strategy.dailyWinRate >= 50 ? 'positive' : 'negative'}>
                                                    {strategy.dailyWinRate}%
                                                </td>
                                                <td>{strategy.totalTrades}</td>
                                                <td className={getProfitClass(strategy.totalProfit)}>
                                                    <strong>{formatProfit(strategy.totalProfit)} U</strong>
                                                </td>
                                            </tr>
                                            {isExpanded && strategy.monthlyResults && (
                                                <tr className="expanded-details-row">
                                                    <td colSpan={6 + allMonths.length}>
                                                        <div className="monthly-details-wrapper">
                                                            {strategy.monthlyResults.map((m, mIdx) => {
                                                                const isSelected = selectedModal?.strategy === strategy && selectedModal?.monthLabel === m.monthLabel;
                                                                return (
                                                                    <div
                                                                        key={mIdx}
                                                                        className={`monthly-detail-card clickable ${isSelected ? 'active' : ''}`}
                                                                        onClick={(e) => toggleDailyDetail(strategy, m.monthLabel, e)}
                                                                    >
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
                                                                );
                                                            })}
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
                                    onClick={() => {
                                        setCurrentPage(prev => Math.max(1, prev - 1));
                                        setExpandedRows([]);
                                        setSelectedModal(null);
                                    }}
                                >
                                    上一页
                                </button>
                                <div className="page-info">
                                    <strong>{currentPage}</strong> / {totalPages}
                                </div>
                                <button
                                    disabled={currentPage === totalPages}
                                    onClick={() => {
                                        setCurrentPage(prev => Math.min(totalPages, prev + 1));
                                        setExpandedRows([]);
                                        setSelectedModal(null);
                                    }}
                                >
                                    下一页
                                </button>
                            </div>
                        )}

                        {/* 每日流水明细侧边栏 (Portal to Body) */}
                        {createPortal(
                            <>
                                {selectedModal && (
                                    <div className="sidebar-overlay" onClick={() => setSelectedModal(null)} />
                                )}
                                <div className={`sidebar-container ${selectedModal ? 'open' : ''}`} onClick={e => e.stopPropagation()}>
                                    {selectedModal && (
                                        <div className="sidebar-content-wrapper">
                                            <div className="sidebar-header">
                                                <div className="sidebar-title">
                                                    <span>📊 {selectedModal.monthLabel} 每日流水详情</span>
                                                    <span className="sidebar-subtitle">
                                                        {formatRankingHours(selectedModal.strategy.rankingHours)} | {selectedModal.strategy.topN}币 | {selectedModal.strategy.entryHour}:00 | {selectedModal.strategy.holdHours}h
                                                    </span>
                                                </div>
                                                <button className="modal-close" onClick={() => setSelectedModal(null)}>✕</button>
                                            </div>
                                            <div className="sidebar-body">
                                                <div className="sidebar-daily-list">
                                                    {(() => {
                                                        const filteredDays = selectedModal.strategy.dailyResults
                                                            ?.filter(d => d.date.startsWith(selectedModal.monthLabel))
                                                            .slice().reverse() || [];

                                                        const totalDetailPages = Math.ceil(filteredDays.length / detailPageSize);
                                                        const paginatedDays = filteredDays.slice(
                                                            (detailPage - 1) * detailPageSize,
                                                            detailPage * detailPageSize
                                                        );

                                                        return (
                                                            <>
                                                                {paginatedDays.map(day => {
                                                                    const isExpanded = expandedDate === day.date;
                                                                    return (
                                                                        <div key={day.date} className="sidebar-daily-row">
                                                                            <div
                                                                                className={`sidebar-daily-summary ${isExpanded ? 'active' : ''}`}
                                                                                onClick={() => setExpandedDate(isExpanded ? null : day.date)}
                                                                            >
                                                                                <span className="d-date">{day.date}</span>
                                                                                <span className="d-counts">
                                                                                    盈利 <strong className="positive">{day.winCount}</strong> / 亏损 <strong className="negative">{day.loseCount}</strong>
                                                                                </span>
                                                                                <span className={`d-profit ${getProfitClass(day.totalProfit)}`}>
                                                                                    {formatProfit(day.totalProfit)} U
                                                                                </span>
                                                                                <span className="expand-icon" style={{ marginLeft: '10px', fontSize: '12px', color: '#999' }}>
                                                                                    {isExpanded ? '▼' : '▶'}
                                                                                </span>
                                                                            </div>
                                                                            {isExpanded && day.trades && (
                                                                                <div className="daily-trades" style={{ background: 'var(--bg-primary)', borderTop: '1px solid var(--border-color)' }}>
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
                                                                                            </span>
                                                                                            <span className="trade-change" style={{ color: 'var(--success)' }}>+{trade.change24h?.toFixed(2)}%</span>
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

                                                                {totalDetailPages > 1 && (
                                                                    <div className="standard-pagination" style={{ margin: '20px' }}>
                                                                        <button
                                                                            disabled={detailPage === 1}
                                                                            onClick={() => setDetailPage(prev => Math.max(1, prev - 1))}
                                                                        >
                                                                            上一页
                                                                        </button>
                                                                        <div className="page-info">
                                                                            <strong>{detailPage}</strong> / {totalDetailPages}
                                                                        </div>
                                                                        <button
                                                                            disabled={detailPage === totalDetailPages}
                                                                            onClick={() => setDetailPage(prev => Math.min(totalDetailPages, prev + 1))}
                                                                        >
                                                                            下一页
                                                                        </button>
                                                                    </div>
                                                                )}
                                                            </>
                                                        );
                                                    })()}
                                                </div>
                                            </div>
                                        </div>
                                    )}
                                </div>
                            </>,
                            document.body
                        )}
                    </div>
                </div>
            )}
        </div>
    )
});

export default OptimizerModule
