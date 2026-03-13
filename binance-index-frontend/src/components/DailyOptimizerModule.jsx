import React, { useState, useEffect, useMemo, memo } from 'react'
import { createPortal } from 'react-dom'
import axios from 'axios'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine, Area, AreaChart } from 'recharts'

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
    const [viewAllDay, setViewAllDay] = useState(null) // 查看全部侧边栏: { date, rankings, loading }
    const [viewAllSort, setViewAllSort] = useState('profit') // 'profit' | 'time'
    const [viewAllSortDir, setViewAllSortDir] = useState('desc') // 'desc' | 'asc'
    const [expandedRows, setExpandedRows] = useState({}) // 展开的行
    const daysPerPage = 10

    // 自动保存参数到 localStorage
    const selectDefaultHours = () => setSelectedEntryHours([0, 12, 18, 22]);
    const selectAllHours = () => setSelectedEntryHours(Array.from({ length: 24 }, (_, i) => i));
    const selectNoneHours = () => setSelectedEntryHours([]);

    // 侧边栏打开时锁定背景滚动
    useEffect(() => {
        const isPanelOpen = viewAllDay;
        if (isPanelOpen) {
            document.body.style.overflow = 'hidden'
        } else {
            document.body.style.overflow = ''
        }
        return () => {
            document.body.style.overflow = ''
        }
    }, [viewAllDay])

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
                setRawData(resp.data.dailyRankings)
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

    // 加载某天全部策略详情
    const loadDayDetail = async (date) => {
        setViewAllDay({ date, rankings: [], loading: true });
        setViewAllSort('time'); // 默认排序时间
        setViewAllSortDir('desc'); // 默认倒序
        setExpandedRows({});
        try {
            const resp = await axios.get('/api/index/backtest/optimize-daily-detail', {
                params: {
                    totalAmount,
                    days,
                    entryHours: selectedEntryHours.join(','),
                    holdHours,
                    rankingHours: selectedRankingHours,
                    timezone: 'Asia/Shanghai',
                    date
                }
            });
            if (resp.data.success) {
                setViewAllDay({ date, rankings: resp.data.rankings, loading: false });
            } else {
                setViewAllDay(prev => prev ? { ...prev, loading: false } : null);
            }
        } catch (err) {
            setViewAllDay(prev => prev ? { ...prev, loading: false } : null);
        }
    }

    // 后端已经返回按日期分组的数据，直接转换格式
    const dailyRankings = useMemo(() => {
        if (!rawData) return null;
        // rawData 是 { date: entries[] } 的 map
        return Object.keys(rawData).sort((a, b) => b.localeCompare(a)).map(date => ({
            date,
            rankings: rawData[date].map(entry => ({
                ...entry,
                label: `${entry.entryHour}:00 | ${entry.rankingHours}h | Top ${entry.topN}`
            }))
        }));
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

            {/* 胜率趋势图 - 使用全量 dailyWinLoss 数据，不受分页影响 */}
            {Object.keys(dailyWinLoss).length > 0 && (
                <div style={{
                    background: 'var(--bg-card, #fff)',
                    borderRadius: '12px',
                    border: '1px solid var(--border-color, #e2e8f0)',
                    padding: '20px',
                    marginBottom: '20px'
                }}>
                    <div style={{ fontSize: '14px', fontWeight: '600', marginBottom: '12px', color: 'var(--text-primary, #1e293b)' }}>
                        📈 每日胜率趋势（全部 {Object.keys(dailyWinLoss).length} 天）
                    </div>
                    <ResponsiveContainer width="100%" height={200}>
                        <AreaChart data={Object.keys(dailyWinLoss).sort().map(date => ({
                            date: date.slice(5), // MM-DD
                            fullDate: date,
                            winRate: dailyWinLoss[date].winRate,
                            win: dailyWinLoss[date].win,
                            lose: dailyWinLoss[date].lose,
                            total: dailyWinLoss[date].total
                        }))} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                            <defs>
                                <linearGradient id="winRateGradient" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="#22c55e" stopOpacity={0.15} />
                                    <stop offset="95%" stopColor="#22c55e" stopOpacity={0.02} />
                                </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                            <XAxis
                                dataKey="date"
                                fontSize={11}
                                tick={{ fill: '#94a3b8' }}
                                interval={Math.max(0, Math.floor(Object.keys(dailyWinLoss).length / 10) - 1)}
                            />
                            <YAxis
                                fontSize={11}
                                tick={{ fill: '#94a3b8' }}
                                domain={[0, 100]}
                                tickFormatter={v => `${v}%`}
                            />
                            <Tooltip
                                contentStyle={{ borderRadius: '8px', fontSize: '12px', border: '1px solid #e2e8f0' }}
                                formatter={(value, name) => [`${value}%`, '胜率']}
                                labelFormatter={(label, payload) => {
                                    if (payload && payload[0]) {
                                        const d = payload[0].payload;
                                        return `${d.fullDate}  赚${d.win} / 亏${d.lose} (共${d.total})`;
                                    }
                                    return label;
                                }}
                            />
                            <ReferenceLine y={50} stroke="#ef4444" strokeDasharray="4 4" strokeOpacity={0.5} />
                            <Area
                                type="monotone"
                                dataKey="winRate"
                                stroke="#22c55e"
                                strokeWidth={2}
                                fill="url(#winRateGradient)"
                                dot={{ r: 3, fill: '#22c55e', strokeWidth: 0 }}
                                activeDot={{ r: 5, fill: '#22c55e', stroke: '#fff', strokeWidth: 2 }}
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </div>
            )}

            {/* 战报内容 */}
            {paginatedRankings && (
                <>
                    <div className="rankings-grid">
                        {paginatedRankings.map(dayData => (
                            <div key={dayData.date} className="day-report-card">
                                <div className="day-report-header">
                                    <div className="date-info" style={{ display: 'flex', alignItems: 'center', flexWrap: 'nowrap' }}>
                                        <span className="date-tag" style={{ whiteSpace: 'nowrap' }}>
                                            {dayData.date}
                                            {dayData.rankings.some(r => r.isLive) && <span className="live-badge" style={{ fontSize: '8px', padding: '0 3px' }}>LIVE</span>}
                                        </span>
                                        <span className="champion-label" style={{
                                            whiteSpace: 'nowrap',
                                            overflow: 'hidden',
                                            textOverflow: 'ellipsis',
                                            marginLeft: '8px',
                                            marginRight: '8px'
                                        }}>🥇 {dayData.rankings[0].label}</span>
                                        {dayData.rankings.length >= topNLimit && (
                                            <button
                                                onClick={(e) => { e.stopPropagation(); loadDayDetail(dayData.date); }}
                                                style={{
                                                    marginLeft: 'auto',
                                                    padding: '2px 8px',
                                                    fontSize: '12px',
                                                    color: 'var(--primary, #007bff)',
                                                    background: 'transparent',
                                                    border: '1px solid var(--primary, #007bff)',
                                                    borderRadius: '4px',
                                                    cursor: 'pointer'
                                                }}
                                            >查看全部</button>
                                        )}
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
                                    {dayData.rankings.map((rank, idx) => {
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

                    {/* 查看全部侧边栏 */}
                    {createPortal(
                        <>
                            {viewAllDay && (
                                <div className="sidebar-overlay" onClick={() => setViewAllDay(null)} />
                            )}
                            <div className={`sidebar-container ${viewAllDay ? 'open' : ''}`} style={{ maxWidth: '1080px' }} onClick={e => e.stopPropagation()}>
                                {viewAllDay && (() => {
                                    if (viewAllDay.loading) {
                                        return (
                                            <div className="sidebar-content-wrapper">
                                                <div className="sidebar-header">
                                                    <div className="sidebar-title">
                                                        <span style={{ fontSize: '20px' }}>📋 {viewAllDay.date} 全部策略排行</span>
                                                    </div>
                                                    <button className="modal-close" onClick={() => setViewAllDay(null)}>✕</button>
                                                </div>
                                                <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', padding: '60px 20px', color: '#888', fontSize: '16px' }}>
                                                    正在加载全部策略数据...
                                                </div>
                                            </div>
                                        );
                                    }
                                    const sorted = [...viewAllDay.rankings].sort((a, b) => {
                                        let res = 0;
                                        if (viewAllSort === 'time') {
                                            // 时间排序：入场时间先后
                                            res = a.entryHour - b.entryHour;
                                        } else {
                                            // 盈亏排序
                                            res = b.profit - a.profit;
                                        }
                                        return viewAllSortDir === 'desc' ? res : -res;
                                    });
                                    return (
                                        <div className="sidebar-content-wrapper">
                                            <div className="sidebar-header">
                                                <div className="sidebar-title">
                                                    <span style={{ fontSize: '20px' }}>📋 {viewAllDay.date} 全部策略排行</span>
                                                    <span className="sidebar-subtitle" style={{ fontSize: '15px' }}>
                                                        共 {viewAllDay.rankings.length} 个组合
                                                        {dailyWinLoss[viewAllDay.date] && (
                                                            <> | <span style={{ color: '#22c55e' }}>赚 {dailyWinLoss[viewAllDay.date].win}</span> / <span style={{ color: '#ef4444' }}>亏 {dailyWinLoss[viewAllDay.date].lose}</span> | {dailyWinLoss[viewAllDay.date].winRate}%</>
                                                        )}
                                                    </span>
                                                </div>
                                                <button className="modal-close" onClick={() => setViewAllDay(null)}>✕</button>
                                            </div>
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 20px 16px', borderBottom: '1px solid var(--border-color, #eee)', background: '#fff' }}>
                                                {/* 左侧：胶囊切换按钮 */}
                                                <div style={{
                                                    display: 'flex',
                                                    background: '#f0f2f5',
                                                    borderRadius: '6px',
                                                    padding: '2px',
                                                    gap: '2px'
                                                }}>
                                                    <button
                                                        onClick={() => setViewAllSort('time')}
                                                        style={{
                                                            padding: '6px 16px', borderRadius: '4px', fontSize: '13px', cursor: 'pointer',
                                                            border: 'none',
                                                            background: viewAllSort === 'time' ? '#fff' : 'transparent',
                                                            color: viewAllSort === 'time' ? '#000' : '#888',
                                                            fontWeight: viewAllSort === 'time' ? '500' : 'normal',
                                                            boxShadow: viewAllSort === 'time' ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                                                            transition: 'all 0.2s'
                                                        }}
                                                    >时间</button>
                                                    <button
                                                        onClick={() => setViewAllSort('profit')}
                                                        style={{
                                                            padding: '6px 16px', borderRadius: '4px', fontSize: '13px', cursor: 'pointer',
                                                            border: 'none',
                                                            background: viewAllSort === 'profit' ? '#fff' : 'transparent',
                                                            color: viewAllSort === 'profit' ? '#000' : '#888',
                                                            fontWeight: viewAllSort === 'profit' ? '500' : 'normal',
                                                            boxShadow: viewAllSort === 'profit' ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                                                            transition: 'all 0.2s'
                                                        }}
                                                    >盈亏</button>
                                                </div>

                                                {/* 右侧：正序/倒序切换按钮 */}
                                                <button
                                                    onClick={() => setViewAllSortDir(viewAllSortDir === 'desc' ? 'asc' : 'desc')}
                                                    style={{
                                                        width: '32px', height: '32px', borderRadius: '4px',
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                        border: '1px solid #ddd', background: '#fff', cursor: 'pointer',
                                                        fontSize: '14px', color: '#666',
                                                        boxShadow: '0 1px 2px rgba(0,0,0,0.05)'
                                                    }}
                                                    title={viewAllSortDir === 'desc' ? "当前降序，点击切换升序" : "当前升序，点击切换降序"}
                                                >
                                                    {viewAllSortDir === 'desc' ? '↓' : '↑'}
                                                </button>
                                            </div>
                                            <div
                                                className="sidebar-body custom-scrollbar"
                                                style={{ padding: '16px 20px 24px 20px', background: '#fff', overflowY: 'auto' }}
                                            >
                                                <div className="daily-trades" style={{ border: '1px solid #eaeaea', borderRadius: '8px', overflow: 'hidden', boxShadow: '0 2px 8px rgba(0,0,0,0.02)' }}>
                                                    {/* 表头 */}
                                                    <div className="trade-header" style={{ gridTemplateColumns: '40px 1.5fr 1fr 100px 80px', fontSize: '14px', padding: '12px 10px' }}>
                                                        <span>#</span>
                                                        <span>策略组合</span>
                                                        <span>胜/负</span>
                                                        <span>盈亏</span>
                                                        <span></span>
                                                    </div>
                                                    {sorted.map((rank, idx) => {
                                                        const rowKey = `${rank.entryHour}-${rank.rankingHours}-${rank.topN}`;
                                                        const isExpanded = expandedRows[rowKey];
                                                        return (
                                                            <React.Fragment key={rowKey}>
                                                                <div
                                                                    className={`trade-row ${rank.profit >= 0 ? '' : 'is-negative'}`}
                                                                    style={{ gridTemplateColumns: '40px 1.5fr 1fr 100px 80px', cursor: 'pointer', borderLeft: `3px solid ${rank.profit >= 0 ? '#22c55e' : '#ef4444'}`, padding: '16px 10px', fontSize: '15px' }}
                                                                    onClick={() => setExpandedRows(prev => ({ ...prev, [rowKey]: !prev[rowKey] }))}
                                                                >
                                                                    <span style={{ color: '#999', fontSize: '14px' }}>{idx + 1}</span>
                                                                    <span>
                                                                        <span className="tag-e" style={{ fontSize: '13px', padding: '2px 6px' }}>{rank.entryHour}:00</span>
                                                                        <span className="tag-h" style={{ marginLeft: '6px', fontSize: '13px', padding: '2px 6px' }}>{rank.rankingHours}h</span>
                                                                        <span className="tag-n" style={{ marginLeft: '6px', fontSize: '13px', padding: '2px 6px' }}>Top {rank.topN}</span>
                                                                        {rank.isLive && <span className="live-badge" style={{ marginLeft: '8px', fontSize: '10px', padding: '2px 4px' }}>LIVE</span>}
                                                                    </span>
                                                                    <span style={{ fontSize: '14px', color: '#888' }}>胜{rank.winCount}/负{rank.loseCount}</span>
                                                                    <span className={rank.profit >= 0 ? 'p-up' : 'p-down'} style={{ fontWeight: '600', fontSize: '15px' }}>
                                                                        {rank.profit > 0 ? '+' : ''}{rank.profit.toFixed(2)}U
                                                                    </span>
                                                                    <span style={{ color: '#aaa', fontSize: '14px', textAlign: 'right' }}>{isExpanded ? '▲' : '▼'}</span>
                                                                </div>
                                                                {isExpanded && rank.trades && (
                                                                    <div style={{ background: 'var(--bg-secondary, #f8f9fa)', padding: '12px 16px', borderRadius: '0 0 6px 6px', marginBottom: '8px' }}>
                                                                        <div className="trade-header" style={{ fontSize: '14px', padding: '8px 0' }}>
                                                                            <span>币种</span>
                                                                            <span>入场涨幅</span>
                                                                            <span>开仓价</span>
                                                                            <span>平仓价</span>
                                                                            <span>涨跌幅</span>
                                                                            <span>盈亏</span>
                                                                        </div>
                                                                        {rank.trades.map((trade, tIdx) => (
                                                                            <div
                                                                                key={tIdx}
                                                                                className={`trade-row ${trade.profitPercent >= 0 ? '' : 'is-negative'}`}
                                                                                style={{ padding: '10px 0', fontSize: '15px', borderBottom: tIdx === rank.trades.length - 1 ? 'none' : '1px solid #eaeaea' }}
                                                                            >
                                                                                <span style={{ fontWeight: '600', color: 'var(--text-primary, #333)' }}>
                                                                                    {trade.symbol.replace('USDT', '')}
                                                                                    {trade.isLive && <span className="live-badge" style={{ marginLeft: '6px', fontSize: '11px', padding: '2px 4px' }}>LIVE</span>}
                                                                                </span>
                                                                                <span>{trade.change24h > 0 ? '+' : ''}{trade.change24h.toFixed(2)}%</span>
                                                                                <span style={{ fontFamily: 'monospace' }}>{trade.entryPrice.toFixed(4)}</span>
                                                                                <span style={{ fontFamily: 'monospace' }}>{trade.exitPrice?.toFixed(4) || '-'}</span>
                                                                                <span className={trade.profitPercent >= 0 ? 'p-up' : 'p-down'}>
                                                                                    {trade.profitPercent > 0 ? '+' : ''}{trade.profitPercent.toFixed(2)}%
                                                                                </span>
                                                                                <span className={trade.profit >= 0 ? 'p-up' : 'p-down'} style={{ fontWeight: '500' }}>
                                                                                    {trade.profit > 0 ? '+' : ''}{trade.profit.toFixed(2)}U
                                                                                </span>
                                                                            </div>
                                                                        ))}
                                                                    </div>
                                                                )}
                                                            </React.Fragment>
                                                        );
                                                    })}
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })()}
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
