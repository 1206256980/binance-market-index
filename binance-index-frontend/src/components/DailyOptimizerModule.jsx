import React, { useState, useEffect, useMemo } from 'react'
import axios from 'axios'

/**
 * 每日策略优化器模块
 * 用于展示过去 N 天中，每一天表现最好的策略排行
 */
const DailyOptimizerModule = () => {
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

    // 运行状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [rawData, setRawData] = useState(null) // 后端返回的组合原始数据

    // 自动保存参数到 localStorage
    const selectDefaultHours = () => setSelectedEntryHours([0, 12, 18, 22]);
    const selectAllHours = () => setSelectedEntryHours(Array.from({ length: 24 }, (_, i) => i));
    const selectNoneHours = () => setSelectedEntryHours([]);

    useEffect(() => {
        localStorage.setItem('daily_opt_amount', totalAmount)
        localStorage.setItem('daily_opt_days', days)
        localStorage.setItem('daily_opt_entryHours', JSON.stringify(selectedEntryHours))
        localStorage.setItem('daily_opt_holdHours', holdHours)
    }, [totalAmount, days, selectedEntryHours, holdHours])

    const toggleHour = (hour) => {
        if (selectedEntryHours.includes(hour)) {
            setSelectedEntryHours(selectedEntryHours.filter(h => h !== hour));
        } else {
            setSelectedEntryHours([...selectedEntryHours, hour].sort((a, b) => a - b));
        }
    }

    // 执行优化计算
    const runOptimize = async () => {
        setLoading(true)
        setError(null)
        try {
            const resp = await axios.get('/api/index/backtest/optimize-daily', {
                params: {
                    totalAmount,
                    days,
                    entryHours: selectedEntryHours.join(','),
                    holdHours,
                    timezone: 'Asia/Shanghai'
                }
            })
            if (resp.data.success) {
                setRawData(resp.data.combinations)
            } else {
                setError(resp.data.message)
            }
        } catch (err) {
            setError('请求失败: ' + (err.response?.data?.message || err.message))
        } finally {
            setLoading(false)
        }
    }

    // 数据处理核心逻辑：将“组合列表 -> 每日结果” 转换为 “每日结果 -> 组合排行”
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
                    loseCount: dr.loseCount
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
                    <div className="action-item">
                        <button
                            className={`run-btn ${loading ? 'loading' : ''}`}
                            onClick={runOptimize}
                            disabled={loading}
                        >
                            {loading ? '正在复盘...' : '开始挖掘'}
                        </button>
                    </div>
                </div>
                {error && <div className="error-banner">{error}</div>}
            </div>

            {/* 战报内容 */}
            {dailyRankings && (
                <div className="rankings-grid">
                    {dailyRankings.map(dayData => (
                        <div key={dayData.date} className="day-report-card">
                            <div className="day-report-header">
                                <div className="date-info">
                                    <span className="date-tag">{dayData.date}</span>
                                    {/* 冠军标记 */}
                                    <span className="champion-label">🥇 {dayData.rankings[0].label}</span>
                                </div>
                                <div className="best-profit">
                                    今日最高盈利: <span className="value">+{dayData.rankings[0].profit.toFixed(2)}U</span>
                                </div>
                            </div>

                            <div className="rank-list">
                                {dayData.rankings.map((rank, idx) => (
                                    <div key={idx} className={`rank-row ${idx === 0 ? 'is-winner' : ''}`}>
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
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {!dailyRankings && !loading && (
                <div className="empty-state">
                    <div className="empty-icon">📈</div>
                    <p>设定好参数并点击“开始挖掘”，我们将为您展现每一天的策略排行榜</p>
                </div>
            )}
        </div>
    )
}

export default DailyOptimizerModule
