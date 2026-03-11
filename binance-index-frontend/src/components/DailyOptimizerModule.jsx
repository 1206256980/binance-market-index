import React, { useState, useEffect, useMemo, memo } from 'react'
import { createPortal } from 'react-dom'
import axios from 'axios'

/**
 * 每日策略优化器模块
 * 用于展示过去 N 天中，每一天表现最好的策略排行
 */
const DailyOptimizerModule = memo(function DailyOptimizerModule() {
    // 参数状态 - 从 localStorage 初始化
    const [totalAmount, setTotalAmount] = useState(() => {
        const val = localStorage.getItem('daily_opt_amount');
        return val ? parseFloat(val) : 1000;
    })
    const [days, setDays] = useState(() => {
        const val = localStorage.getItem('daily_opt_days');
        return val ? parseInt(val) : 30;
    })
    const [selectedEntryHours, setSelectedEntryHours] = useState(() => {
        const val = localStorage.getItem('daily_opt_entryHours');
        return val ? JSON.parse(val) : [0, 12, 18, 22]; // 默认选几个
    })
    const [holdHours, setHoldHours] = useState(() => {
        const val = localStorage.getItem('daily_opt_holdHours');
        return val ? parseInt(val) : 24;
    })
    const [selectedRankingHours, setSelectedRankingHours] = useState(() => {
        const val = localStorage.getItem('daily_opt_rankingHours');
        return val || 'all';
    })
    const [topNLimit, setTopNLimit] = useState(() => {
        const val = localStorage.getItem('daily_opt_topNLimit');
        return val ? parseInt(val) : 20;
    })

    // 运行状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [rawData, setRawData] = useState(null) // 后端返回的组合原始数据
    const [currentPage, setCurrentPage] = useState(1) // 天数分页
    const [pagination, setPagination] = useState(null) // 后端分页元数据
    const [selectedStrategy, setSelectedStrategy] = useState(null) // 当前选中的策略详情 { date, strategy }
    const [dailyWinLoss, setDailyWinLoss] = useState({}) // 后端返回的每日赢亏统计
    const daysPerPage = 10

    // 自动保存参数到 localStorage
    const selectDefaultHours = () => setSelectedEntryHours([0, 12, 18, 22]);
    const selectAllHours = () => setSelectedEntryHours(Array.from({ length: 24 }, (_, i) => i));
    const selectNoneHours = () => setSelectedEntryHours([]);

    useEffect(() => {
        localStorage.setItem('daily_opt_amount', totalAmount)
        localStorage.setItem('daily_opt_days', days)
        localStorage.setItem('daily_opt_entryHours', JSON.stringify(selectedEntryHours))
        localStorage.setItem('daily_opt_holdHours', holdHours)
        localStorage.setItem('daily_opt_rankingHours', selectedRankingHours)
        localStorage.setItem('daily_opt_topNLimit', topNLimit)
    }, [totalAmount, days, selectedEntryHours, holdHours, selectedRankingHours, topNLimit])

    // 侧边栏打开时锁定body滚动
    useEffect(() => {
        if (selectedStrategy) {
            document.body.style.overflow = 'hidden'
        } else {
            document.body.style.overflow = ''
        }
        return () => {
            document.body.style.overflow = ''
        }
    }, [selectedStrategy])

    const toggleHour = (hour) => {
        if (selectedEntryHours.includes(hour)) {
            setSelectedEntryHours(selectedEntryHours.filter(h => h !== hour));
        } else {
            setSelectedEntryHours([...selectedEntryHours, hour].sort((a, b) => a - b));
        }
    }

    // 执行优化计算（支持分页）
    const runOptimize = async (page = 1) => {
        setLoading(true)
        setError(null)
        try {
            const resp = await axios.get('/api/index/backtest/optimize-daily', {
                params: {
                    totalAmount,
                    days,
                    entryHours: selectedEntryHours.join(','),
                    holdHours,
                    rankingHours: selectedRankingHours,
                    topNLimit,
                    timezone: 'Asia/Shanghai',
                    page,
                    pageSize: daysPerPage
                }
            })
            if (resp.data.success) {
                setRawData(resp.data.combinations)
                setPagination(resp.data.pagination)
                setDailyWinLoss(resp.data.dailyWinLoss || {})
                setCurrentPage(page)
            } else {
                setError(resp.data.message)
            }
        } catch (err) {
            setError('请求失败: ' + (err.response?.data?.message || err.message))
        } finally {
            setLoading(false)
        }
    }

    // 数据处理核心逻辑：将"组合列表 -> 每日结果" 转换为 "每日结果 -> 组合排行"
    const dailyRankings = useMemo(() => {
        if (!rawData) return null;

        const dateMap = {};
        rawData.forEach(combo => {
            const label = `${combo.entryHour}:00 | ${combo.rankingHours}h | Top ${combo.topN}`;
            combo.dailyResults.forEach(dr => {
                if (!dateMap[dr.date]) {
                    dateMap[dr.date] = [];
                }
                dateMap[dr.date].push({
                    label,
                    entryHour: combo.entryHour,
                    rankingHours: combo.rankingHours,
                    topN: combo.topN,
                    profit: dr.totalProfit,
                    winCount: dr.winCount,
                    loseCount: dr.loseCount,
                    isLive: dr.isLive,
                    trades: dr.trades
                });
            });
        });

        // 对日期进行倒序排列（最近的日期在前）
        const sortedDates = Object.keys(dateMap).sort((a, b) => b.localeCompare(a));

        return sortedDates.map(date => {
            // 对每一天内的策略按盈利从高到低排序
            const rankings = dateMap[date].sort((a, b) => b.profit - a.profit);
            return {
                date,
                rankings
            };
        });
    }, [rawData]);

    // 使用后端分页，不再前端切片
    const paginatedRankings = dailyRankings;
    const totalPages = pagination?.totalPages || (dailyRankings ? Math.ceil(dailyRankings.length / daysPerPage) : 0);

    return (
        <div className="daily-optimizer-module">
            {/* 配置面板 */}
            <div className="config-card">
                <div className="config-header">
                    <h3>📅 每日策略战报</h3>
                    <p>自动测试多种涨幅榜周期与币种数量，为您找出历史每一天的最优解</p>
                </div>

                <div className="config-grid">
                    <div className="param-item">
                        <label>总金额 (U)</label>
                        <input
                            type="number"
                            className="input-field"
                            value={totalAmount}
                            onChange={e => setTotalAmount(e.target.value)}
                        />
                    </div>
                    <div className="param-item">
                        <label>回测天数</label>
                        <input
                            type="number"
                            className="input-field"
                            value={days}
                            onChange={e => setDays(e.target.value)}
                        />
                    </div>
                    <div className="param-item wide hour-selection-section">
                        <div className="label-with-actions">
                            <label>入场时间 ({selectedEntryHours.length})</label>
                            <div className="quick-btns">
                                <button type="button" onClick={selectDefaultHours}>默认</button>
                                <button type="button" onClick={selectAllHours}>全选</button>
                                <button type="button" onClick={selectNoneHours}>全清</button>
                            </div>
                        </div>
                        <div className="hour-tags-container">
                            {Array.from({ length: 24 }, (_, i) => (
                                <span
                                    key={i}
                                    className={`hour-tag ${selectedEntryHours.includes(i) ? 'active' : ''}`}
                                    onClick={() => toggleHour(i)}
                                >
                                    {i < 10 ? `0${i}` : i}
                                </span>
                            ))}
                        </div>
                    </div>
                    <div className="param-item">
                        <label>持仓时长 (h)</label>
                        <input
                            type="number"
                            className="input-field"
                            value={holdHours}
                            onChange={e => setHoldHours(e.target.value)}
                        />
                    </div>
                    <div className="param-item">
                        <label>涨幅榜周期</label>
                        <select
                            className="input-field"
                            value={selectedRankingHours}
                            onChange={e => setSelectedRankingHours(e.target.value)}
                            style={{ padding: '6px 8px', cursor: 'pointer' }}
                        >
                            <option value="all">全部</option>
                            <option value="24">24小时</option>
                            <option value="48">48小时</option>
                            <option value="72">72小时</option>
                            <option value="168">7天</option>
                        </select>
                    </div>
                    <div className="param-item">
                        <label>Top前几</label>
                        <input
                            type="number"
                            className="input-field"
                            value={topNLimit}
                            onChange={e => setTopNLimit(e.target.value === '' ? '' : parseInt(e.target.value))}
                            onBlur={e => { if (e.target.value === '' || isNaN(topNLimit)) setTopNLimit(20) }}
                            style={{ width: '60px' }}
                        />
                    </div>
                    <div className="action-item">
                        <button
                            className={`run-btn ${loading ? 'loading' : ''}`}
                            onClick={() => runOptimize(1)}
                            disabled={loading}
                        >
                            {loading ? '正在复盘...' : '开始挖掘'}
                        </button>
                    </div>
                </div>
                {error && <div className="error-banner">{error}</div>}
            </div>

            {/* 战报内容 */}
            {paginatedRankings && (
                <>
                    <div className="rankings-grid">
                        {paginatedRankings.map(dayData => (
                            <div key={dayData.date} className="day-report-card">
                                <div className="day-report-header">
                                    <div className="date-info">
                                        <span className="date-tag">
                                            {dayData.date}
                                            {dayData.rankings.some(r => r.isLive) && <span className="live-badge" style={{ fontSize: '8px', padding: '0 3px' }}>LIVE</span>}
                                        </span>
                                        <span className="champion-label">🥇 {dayData.rankings[0].label}</span>
                                    </div>
                                    <div className="best-profit">
                                        最高盈利: <span className="value" style={{ color: dayData.rankings[0].profit >= 0 ? '#22c55e' : '#ef4444' }}>{dayData.rankings[0].profit > 0 ? '+' : ''}{dayData.rankings[0].profit.toFixed(2)}U</span>
                                        {dailyWinLoss[dayData.date] && (
                                            <span style={{ marginLeft: '12px', fontSize: '12px', fontWeight: '400' }}>
                                                <span style={{ color: '#22c55e' }}>赚 {dailyWinLoss[dayData.date].win}</span>
                                                <span style={{ margin: '0 4px', color: '#999' }}>/</span>
                                                <span style={{ color: '#ef4444' }}>亏 {dailyWinLoss[dayData.date].lose}</span>
                                                <span style={{ margin: '0 4px', color: '#999' }}>|</span>
                                                <span style={{ color: dailyWinLoss[dayData.date].winRate >= 50 ? '#22c55e' : '#ef4444', fontWeight: '500' }}>
                                                    {dailyWinLoss[dayData.date].winRate}%
                                                </span>
                                            </span>
                                        )}
                                    </div>
                                </div>

                                <div className="rank-list">
                                    {dayData.rankings.slice(0, topNLimit).map((rank, idx) => {
                                        const isSelected = selectedStrategy?.date === dayData.date && selectedStrategy?.strategy === rank;

                                        return (
                                            <div
                                                key={idx}
                                                className={`rank-row ${idx === 0 ? 'is-winner' : ''} ${isSelected ? 'active' : ''}`}
                                                onClick={() => setSelectedStrategy(isSelected ? null : { date: dayData.date, strategy: rank })}
                                                style={{ cursor: 'pointer' }}
                                            >
                                                <div className="rank-pos">{idx + 1}</div>
                                                <div className="strategy-meta">
                                                    <span className="tag-e">{rank.entryHour}:00</span>
                                                    <span className="tag-h">{rank.rankingHours}h</span>
                                                    <span className="tag-n">Top {rank.topN}</span>
                                                </div>
                                                <div className="rank-stats">
                                                    <span className={`p-val ${rank.profit >= 0 ? 'p-up' : 'p-down'}`}>
                                                        {rank.profit > 0 ? '+' : ''}{rank.profit.toFixed(1)}U
                                                    </span>
                                                    <span className="w-l">胜{rank.winCount}/负{rank.loseCount}</span>
                                                </div>
                                            </div>
                                        );
                                    })}
                                    {dayData.rankings.length > topNLimit && (
                                        <div className="more-hint" style={{ textAlign: 'center', padding: '10px', fontSize: '12px', color: '#888' }}>
                                            ... 还有 {dayData.rankings.length - topNLimit} 个组合未列出
                                        </div>
                                    )}
                                </div>
                            </div>
                        ))}
                    </div>

                    {/* 分页控制 UI */}
                    {totalPages > 1 && (
                        <div className="standard-pagination" style={{
                            marginTop: '25px',
                            display: 'flex',
                            justifyContent: 'center',
                            gap: '15px',
                            alignItems: 'center',
                            padding: '20px'
                        }}>
                            <button
                                disabled={currentPage === 1 || loading}
                                onClick={() => {
                                    runOptimize(currentPage - 1);
                                    window.scrollTo({ top: 0, behavior: 'smooth' });
                                }}
                                style={{
                                    padding: '8px 20px',
                                    borderRadius: '6px',
                                    border: '1px solid var(--border-color, #ddd)',
                                    background: 'var(--bg-card, #fff)',
                                    cursor: (currentPage === 1 || loading) ? 'not-allowed' : 'pointer',
                                    opacity: (currentPage === 1 || loading) ? 0.5 : 1
                                }}
                            >
                                ← 上一页
                            </button>
                            <div className="page-info" style={{ fontSize: '14px', fontWeight: '600' }}>
                                第 <span style={{ color: 'var(--primary, #007bff)' }}>{currentPage}</span> / {totalPages} 页
                                <span style={{ marginLeft: '10px', color: '#888', fontWeight: '400' }}>(共 {pagination?.totalDays || 0} 天)</span>
                            </div>
                            <button
                                disabled={currentPage === totalPages || loading}
                                onClick={() => {
                                    runOptimize(currentPage + 1);
                                    window.scrollTo({ top: 0, behavior: 'smooth' });
                                }}
                                style={{
                                    padding: '8px 20px',
                                    borderRadius: '6px',
                                    border: '1px solid var(--border-color, #ddd)',
                                    background: 'var(--bg-card, #fff)',
                                    cursor: (currentPage === totalPages || loading) ? 'not-allowed' : 'pointer',
                                    opacity: (currentPage === totalPages || loading) ? 0.5 : 1
                                }}
                            >
                                下一页 →
                            </button>
                        </div>
                    )}

                    {/* 侧边栏 - Portal到body */}
                    {createPortal(
                        <>
                            {selectedStrategy && (
                                <div className="sidebar-overlay" onClick={() => setSelectedStrategy(null)} />
                            )}
                            <div className={`sidebar-container ${selectedStrategy ? 'open' : ''}`} onClick={e => e.stopPropagation()}>
                                {selectedStrategy && (
                                    <div className="sidebar-content-wrapper">
                                        <div className="sidebar-header">
                                            <div className="sidebar-title">
                                                <span>📊 {selectedStrategy.date} 交易明细</span>
                                                <span className="sidebar-subtitle">
                                                    {selectedStrategy.strategy.entryHour}:00 | {selectedStrategy.strategy.rankingHours}h | Top {selectedStrategy.strategy.topN}
                                                </span>
                                            </div>
                                            <button className="modal-close" onClick={() => setSelectedStrategy(null)}>✕</button>
                                        </div>
                                        <div className="sidebar-body">
                                            {selectedStrategy.strategy.trades && (
                                                <div className="daily-trades">
                                                    <div className="trade-header">
                                                        <span>币种</span>
                                                        <span>入场涨幅</span>
                                                        <span>开仓价</span>
                                                        <span>平仓价</span>
                                                        <span>盈亏%</span>
                                                        <span>盈亏U</span>
                                                    </div>
                                                    {selectedStrategy.strategy.trades.map((trade, tIdx) => (
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
                                    </div>
                                )}
                            </div>
                        </>,
                        document.body
                    )}
                </>
            )}

            {!dailyRankings && !loading && (
                <div className="empty-state">
                    <div className="empty-icon">📈</div>
                    <p>设定好参数并点击"开始挖掘"，我们将为您展现每一天的策略排行榜</p>
                </div>
            )}
        </div>
    )
});

export default DailyOptimizerModule
