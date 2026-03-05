import { useState, useEffect, memo, useRef } from 'react'
import { createPortal } from 'react-dom'
import axios from 'axios'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'

/**
 * 币种选择器组件 - 使用原生checkbox实现
 * 定义在外部以防止父组件重绘时导致内部状态（如isOpen）丢失
 */
const SymbolSelector = ({ symbols, selectedSymbols, onChange, loadingSymbols }) => {
    const [isOpen, setIsOpen] = useState(false);
    const [searchTerm, setSearchTerm] = useState('');
    const dropdownRef = useRef(null);

    // 按涨幅倒序排序
    const sortedSymbols = [...symbols].sort((a, b) => b.priceChangePercent - a.priceChangePercent);

    // 过滤搜索
    const filteredSymbols = sortedSymbols.filter(s =>
        s.symbol.toLowerCase().includes(searchTerm.toLowerCase())
    );

    // 处理选择/取消选择
    const handleToggle = (symbol) => {
        if (selectedSymbols.includes(symbol)) {
            onChange(selectedSymbols.filter(s => s !== symbol));
        } else {
            onChange([...selectedSymbols, symbol]);
        }
    };

    // 点击外部关闭
    useEffect(() => {
        const handleClickOutside = (event) => {
            if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    return (
        <div ref={dropdownRef} style={{ position: 'relative', width: '100%', minWidth: '450px' }}>
            {/* 输入框 */}
            <div
                onClick={(e) => {
                    // 只有点击空白区域才toggle，点击标签不触发
                    if (e.target === e.currentTarget) {
                        setIsOpen(!isOpen);
                    }
                }}
                style={{
                    minHeight: '42px',
                    borderRadius: '8px',
                    border: `2px solid ${isOpen ? '#667eea' : '#d1d5db'}`,
                    padding: '6px 12px',
                    cursor: 'pointer',
                    boxShadow: isOpen ? '0 0 0 3px rgba(102, 126, 234, 0.1)' : 'none',
                    display: 'flex',
                    flexWrap: 'wrap',
                    gap: '4px',
                    alignItems: 'center',
                    transition: 'all 0.2s',
                    backgroundColor: 'white'
                }}
            >
                {selectedSymbols.length === 0 ? (
                    <span
                        onClick={() => setIsOpen(true)}
                        style={{ color: '#9ca3af', fontSize: '14px', width: '100%' }}
                    >
                        搜索并选择币种...
                    </span>
                ) : (
                    selectedSymbols.map(symbol => (
                        <div
                            key={symbol}
                            onClick={(e) => e.stopPropagation()} // 点击标签不触发toggle
                            style={{
                                backgroundColor: '#e0e7ff',
                                borderRadius: '6px',
                                padding: '4px 8px',
                                fontSize: '13px',
                                color: '#4338ca',
                                fontWeight: '500',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '4px'
                            }}
                        >
                            <span>{symbol.replace('USDT', '')}</span>
                            <button
                                onClick={(e) => {
                                    e.stopPropagation();
                                    onChange(selectedSymbols.filter(s => s !== symbol));
                                }}
                                style={{
                                    background: 'none',
                                    border: 'none',
                                    color: '#6366f1',
                                    cursor: 'pointer',
                                    padding: '0 2px',
                                    fontSize: '16px',
                                    lineHeight: '1'
                                }}
                            >
                                ×
                            </button>
                        </div>
                    ))
                )}
            </div>

            {/* 下拉菜单 */}
            {isOpen && (
                <div
                    onClick={(e) => e.stopPropagation()} // 阻止事件冒泡，点击下拉框内部不关闭
                    style={{
                        position: 'absolute',
                        top: '100%',
                        left: 0,
                        right: 0,
                        marginTop: '4px',
                        backgroundColor: 'white',
                        borderRadius: '8px',
                        boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05)',
                        border: '1px solid #e5e7eb',
                        zIndex: 2000,
                        maxHeight: '320px',
                        display: 'flex',
                        flexDirection: 'column'
                    }}>
                    {/* 搜索框 */}
                    <div style={{ padding: '8px', borderBottom: '1px solid #e5e7eb' }}>
                        <input
                            type="text"
                            placeholder="搜索币种..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            onClick={(e) => e.stopPropagation()}
                            style={{
                                width: '100%',
                                padding: '8px 12px',
                                border: '1px solid #d1d5db',
                                borderRadius: '6px',
                                fontSize: '14px',
                                outline: 'none'
                            }}
                        />
                    </div>

                    {/* 选项列表 */}
                    <div style={{
                        overflowY: 'auto',
                        padding: '4px',
                        maxHeight: '260px'
                    }}>
                        {loadingSymbols ? (
                            <div style={{ padding: '20px', textAlign: 'center', color: '#64748b' }}>
                                加载中...
                            </div>
                        ) : filteredSymbols.length === 0 ? (
                            <div style={{ padding: '20px', textAlign: 'center', color: '#64748b' }}>
                                未找到匹配的币种
                            </div>
                        ) : (
                            filteredSymbols.map(item => {
                                const isSelected = selectedSymbols.includes(item.symbol);
                                const pct = item.priceChangePercent || 0;
                                const color = pct > 0 ? '#0ecb81' : '#f6465d';

                                return (
                                    <div
                                        key={item.symbol}
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            handleToggle(item.symbol);
                                        }}
                                        style={{
                                            display: 'flex',
                                            alignItems: 'center',
                                            padding: '8px 12px',
                                            margin: '2px 0',
                                            borderRadius: '6px',
                                            cursor: 'pointer',
                                            backgroundColor: isSelected ? '#667eea' : 'transparent',
                                            color: isSelected ? 'white' : '#1f2937',
                                            transition: 'background-color 0.15s',
                                            userSelect: 'none'
                                        }}
                                        onMouseEnter={(e) => {
                                            if (!isSelected) e.currentTarget.style.backgroundColor = '#f3f4f6';
                                        }}
                                        onMouseLeave={(e) => {
                                            if (!isSelected) e.currentTarget.style.backgroundColor = 'transparent';
                                        }}
                                    >
                                        <input
                                            type="checkbox"
                                            checked={isSelected}
                                            onChange={() => { }} // 空函数，实际通过div的onClick处理
                                            onClick={(e) => e.stopPropagation()}
                                            style={{
                                                marginRight: '10px',
                                                cursor: 'pointer',
                                                width: '16px',
                                                height: '16px',
                                                pointerEvents: 'none' // 禁用checkbox本身的点击，完全通过div处理
                                            }}
                                        />
                                        <span style={{ flex: 1, fontSize: '14px', fontWeight: '500' }}>
                                            {item.symbol.replace('USDT', '')}
                                        </span>
                                        <span style={{
                                            color: isSelected ? 'white' : color,
                                            fontWeight: 'bold',
                                            fontSize: '13px',
                                            minWidth: '70px',
                                            textAlign: 'right'
                                        }}>
                                            {pct > 0 ? '+' : ''}{pct.toFixed(2)}%
                                        </span>
                                    </div>
                                );
                            })
                        )}
                    </div>
                </div>
            )}
        </div>
    );
};

/**
 * 实时持仓监控模块
 */
const LiveMonitorModule = memo(function LiveMonitorModule() {
    // 模式选择
    const [mode, setMode] = useState(() => {
        return localStorage.getItem('lm_mode') || 'ranking';
    })

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
    const [refreshInterval, setRefreshInterval] = useState(() => {
        const value = localStorage.getItem('lm_refreshInterval');
        return value !== null ? parseInt(value) : 30;
    })

    // 手动选币相关
    const [selectedSymbols, setSelectedSymbols] = useState(() => {
        const saved = localStorage.getItem('lm_selectedSymbols');
        return saved ? JSON.parse(saved) : [];
    })
    const [availableSymbols, setAvailableSymbols] = useState([])
    const [loadingSymbols, setLoadingSymbols] = useState(false)

    // 回溯模式
    const [isBacktrackMode, setIsBacktrackMode] = useState(false)
    const [backtrackTime, setBacktrackTime] = useState('')

    // 参数自动保存到 localStorage
    useEffect(() => {
        localStorage.setItem('lm_rankingHours', rankingHours)
        localStorage.setItem('lm_topN', topN)
        localStorage.setItem('lm_hourlyAmount', hourlyAmount)
        localStorage.setItem('lm_monitorHours', monitorHours)
        localStorage.setItem('lm_refreshInterval', refreshInterval)
    }, [rankingHours, topN, hourlyAmount, monitorHours, refreshInterval])

    // 保存模式和选币到localStorage
    useEffect(() => {
        localStorage.setItem('lm_mode', mode)
    }, [mode])

    // 保存选币到后端（防抖）
    useEffect(() => {
        if (mode === 'manual' && selectedSymbols.length > 0) {
            const timer = setTimeout(async () => {
                try {
                    await axios.post('/api/index/live-monitor/selected-symbols', {
                        symbols: selectedSymbols
                    })
                    console.log('保存选择币种成功')
                } catch (error) {
                    console.error('保存选择币种失败:', error)
                }
            }, 500) // 500ms防抖

            return () => clearTimeout(timer)
        }
    }, [selectedSymbols, mode])

    // 获取所有可用币种和已保存的选择
    useEffect(() => {
        if (mode === 'manual') {
            fetchAvailableSymbols()
            fetchSavedSymbols() // 从后端获取已保存的币种
        }
    }, [mode])

    const fetchSavedSymbols = async () => {
        try {
            const res = await axios.get('/api/index/live-monitor/selected-symbols')
            if (res.data.success && res.data.symbols) {
                setSelectedSymbols(res.data.symbols)
            }
        } catch (error) {
            console.error('获取已保存币种失败:', error)
        }
    }

    const fetchAvailableSymbols = async () => {
        setLoadingSymbols(true)
        try {
            const res = await axios.get('/api/index/symbols/tickers')
            if (res.data.success) {
                setAvailableSymbols(res.data.symbols)
            }
        } catch (error) {
            console.error('获取币种列表失败:', error)
        } finally {
            setLoadingSymbols(false)
        }
    }

    // 状态
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [result, setResult] = useState(null)
    const [expandedHours, setExpandedHours] = useState([])
    const [trackingData, setTrackingData] = useState(null) // 逐小时追踪数据
    const [expandedSnapshots, setExpandedSnapshots] = useState([]) // 逐小时追踪的展开状态
    const [autoRefresh, setAutoRefresh] = useState(false) // 自动刷新开关

    // 价格指数图状态
    const [priceIndexData, setPriceIndexData] = useState(null)
    const [priceIndexGranularity, setPriceIndexGranularity] = useState(() => {
        const value = localStorage.getItem('lm_priceIndexGranularity');
        return value !== null ? parseInt(value) : 15; // 默认 15 分钟
    })

    // 使用 ref 跟踪最新值，避免 setInterval 闭包过时问题
    const trackingDataRef = useRef(trackingData);
    const priceIndexDataRef = useRef(priceIndexData);
    useEffect(() => { trackingDataRef.current = trackingData; }, [trackingData]);
    useEffect(() => { priceIndexDataRef.current = priceIndexData; }, [priceIndexData]);

    // 价格指数颗粒度缓存到 localStorage
    useEffect(() => {
        localStorage.setItem('lm_priceIndexGranularity', priceIndexGranularity)
    }, [priceIndexGranularity])

    // 侧边栏打开时锁定body滚动
    useEffect(() => {
        if (trackingData || priceIndexData) {
            document.body.style.overflow = 'hidden'
        } else {
            document.body.style.overflow = ''
        }
        return () => {
            document.body.style.overflow = ''
        }
    }, [trackingData, priceIndexData])

    // 自动刷新逻辑
    useEffect(() => {
        if (!autoRefresh || !result) {
            return; // 没有开启自动刷新或没有数据时不执行
        }

        const intervalId = setInterval(async () => {
            console.log(`⏰ 自动刷新: ${new Date().toLocaleTimeString()}`);

            // 刷新主页面数据
            await refreshMainData();

            // 如果侧边栏打开，同时刷新侧边栏数据（通过 ref 读取最新值）
            if (trackingDataRef.current) {
                await refreshTrackingData();
            }
            if (priceIndexDataRef.current) {
                await refreshPriceIndexData();
            }
        }, refreshInterval * 1000);

        return () => clearInterval(intervalId);
    }, [autoRefresh, result, refreshInterval]);

    // 刷新主页面数据
    const refreshMainData = async () => {
        try {
            let res;

            if (isBacktrackMode) {
                // 回溯模式
                let apiUrl = '/api/index/live-monitor';
                const params = {
                    rankingHours,
                    topN,
                    hourlyAmount,
                    monitorHours,
                    timezone: 'Asia/Shanghai',
                    backtrackTime: backtrackTime.replace('T', ' ') + ':00'
                };

                if (mode === 'manual' && selectedSymbols.length > 0) {
                    apiUrl = '/api/index/live-monitor/manual';
                    params.symbols = selectedSymbols.join(',');
                }

                res = await axios.get(apiUrl, { params });
            } else {
                // 实时模式
                if (mode === 'ranking') {
                    const params = {
                        rankingHours,
                        topN,
                        hourlyAmount,
                        monitorHours,
                        timezone: 'Asia/Shanghai'
                    };
                    res = await axios.get('/api/index/live-monitor', { params });
                } else {
                    if (selectedSymbols.length === 0) return;

                    const params = {
                        symbols: selectedSymbols.join(','),
                        hourlyAmount,
                        monitorHours,
                        timezone: 'Asia/Shanghai'
                    };
                    res = await axios.get('/api/index/live-monitor/manual', { params });
                }
            }

            if (res.data.success) {
                setResult(res.data);
            }
        } catch (err) {
            console.error('自动刷新主页面失败:', err);
        }
    };

    // 刷新追踪数据
    const refreshTrackingData = async () => {
        if (!trackingData) return;

        try {
            const params = {
                entryTime: trackingData.entryTime,
                rankingHours,
                topN,
                totalAmount: hourlyAmount,
                monitorHours,
                timezone: 'Asia/Shanghai'
            };

            if (mode === 'manual' && selectedSymbols.length > 0) {
                params.symbols = selectedSymbols.join(',');
            }

            const res = await axios.get('/api/index/live-monitor/hourly-tracking', { params });

            if (res.data.success) {
                setTrackingData(res.data.data);
            }
        } catch (err) {
            console.error('自动刷新追踪数据失败:', err);
        }
    };

    // 刷新价格指数数据
    const refreshPriceIndexData = async () => {
        if (!priceIndexData) return;

        try {
            const params = {
                entryTime: priceIndexData.entryTime,
                rankingHours,
                topN,
                granularity: priceIndexGranularity,
                lookbackHours: 24,
                timezone: 'Asia/Shanghai'
            };

            if (mode === 'manual' && selectedSymbols.length > 0) {
                params.symbols = selectedSymbols.join(',');
            }

            const res = await axios.get('/api/index/live-monitor/price-index', { params });

            if (res.data.success) {
                setPriceIndexData(res.data.data);
            }
        } catch (err) {
            console.error('自动刷新价格指数失败:', err);
        }
    };

    const runMonitor = async () => {
        setLoading(true)
        setError(null)
        setExpandedHours([]) // 重置展开行
        setIsBacktrackMode(false) // 点击开始监控时重置为实时模式
        setBacktrackTime('')

        try {
            let res;

            if (mode === 'ranking') {
                // 涨幅榜模式
                const params = {
                    rankingHours,
                    topN,
                    hourlyAmount,
                    monitorHours,
                    timezone: 'Asia/Shanghai'
                }
                res = await axios.get('/api/index/live-monitor', { params })
            } else {
                // 手动选币模式
                if (selectedSymbols.length === 0) {
                    setError('请至少选择一个币种')
                    setLoading(false)
                    return
                }

                const params = {
                    symbols: selectedSymbols.join(','),
                    hourlyAmount,
                    monitorHours,
                    timezone: 'Asia/Shanghai'
                }
                res = await axios.get('/api/index/live-monitor/manual', { params })
            }

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
            const params = {
                entryTime: hourData.entryTime,
                rankingHours,
                topN,
                totalAmount: hourlyAmount,
                monitorHours,
                timezone: 'Asia/Shanghai'
            };

            // 手动选币模式：传递symbols参数
            if (mode === 'manual' && selectedSymbols.length > 0) {
                params.symbols = selectedSymbols.join(',');
            }

            const res = await axios.get('/api/index/live-monitor/hourly-tracking', { params })

            if (res.data.success) {
                setTrackingData(res.data.data)
            } else {
                console.error('追踪失败:', res.data.message)
            }
        } catch (err) {
            console.error('追踪请求失败:', err)
        }
    }

    /**
     * 回溯功能：以选定的时间点作为"当前时间"重新计算持仓监控
     * 点击某一行的回溯按钮，将该行的时间作为回溯时间
     */
    const handleBacktrackClick = async (hourStr) => {
        setLoading(true)
        setError(null)
        setExpandedHours([])
        setIsBacktrackMode(true)
        setBacktrackTime(hourStr)

        try {
            let apiUrl = '/api/index/live-monitor';
            const params = {
                rankingHours,
                topN,
                hourlyAmount,
                monitorHours,
                timezone: 'Asia/Shanghai',
                backtrackTime: hourStr.replace('T', ' ') + ':00'
            };

            // 手动选币模式：使用manual接口
            if (mode === 'manual' && selectedSymbols.length > 0) {
                apiUrl = '/api/index/live-monitor/manual';
                params.symbols = selectedSymbols.join(',');
            }

            const res = await axios.get(apiUrl, { params })

            if (res.data.success) {
                setResult(res.data)
            } else {
                setError(res.data.message || '回溯失败')
            }
        } catch (err) {
            console.error('回溯请求失败:', err)
            setError(err.response?.data?.message || err.message || '请求失败')
        } finally {
            setLoading(false)
        }
    }

    /**
     * 价格指数图功能：获取指定入场时间的详细价格指数数据
     */
    const handlePriceIndexClick = async (hourStr, granularity = priceIndexGranularity) => {
        try {
            const params = {
                entryTime: hourStr,
                rankingHours,
                topN,
                granularity,
                lookbackHours: 24,
                timezone: 'Asia/Shanghai'
            };

            // 手动选币模式：传递symbols参数
            if (mode === 'manual' && selectedSymbols.length > 0) {
                params.symbols = selectedSymbols.join(',');
            }

            const res = await axios.get('/api/index/live-monitor/price-index', { params })

            if (res.data.success) {
                setPriceIndexData(res.data.data)
                setPriceIndexGranularity(granularity)
            } else {
                console.error('获取价格指数失败:', res.data.message)
            }
        } catch (err) {
            console.error('价格指数请求失败:', err)
        }
    }


    const handleCopySymbol = (symbol) => {
        if (!symbol) return;

        // 优先使用 navigator.clipboard
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(symbol).then(() => {
                console.log('Copied (Modern):', symbol);
            }).catch(err => {
                console.error('Modern copy failed, trying fallback:', err);
                copyToClipboardFallback(symbol);
            });
        } else {
            // 降级方案
            copyToClipboardFallback(symbol);
        }
    }

    const copyToClipboardFallback = (text) => {
        const textArea = document.createElement("textarea");
        textArea.value = text;

        // 确保 textarea 在移动端和桌面端都不可见
        textArea.style.position = "fixed";
        textArea.style.left = "-9999px";
        textArea.style.top = "0";
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();

        try {
            const successful = document.execCommand('copy');
            if (successful) {
                console.log('Copied (Fallback):', text);
            } else {
                console.error('Fallback copy failed');
            }
        } catch (err) {
            console.error('Fallback copy error:', err);
        }

        document.body.removeChild(textArea);
    }



    return (
        <div className="backtest-module">
            <div className="backtest-header">
                <div className="backtest-title">📊 实时持仓监控</div>
                <div className="backtest-subtitle">监控每个整点小时做空涨幅榜的实时盈亏情况</div>
            </div>

            {/* 参数输入区 */}
            <div className="backtest-params">
                {/* 模式选择 - 使用开关 */}
                <div className="param-group">
                    <label>选币方式</label>
                    <div style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '12px',
                        height: '38px'
                    }}>
                        <span style={{
                            fontSize: '14px',
                            color: mode === 'ranking' ? '#667eea' : '#64748b',
                            fontWeight: mode === 'ranking' ? '600' : '400',
                            transition: 'all 0.2s'
                        }}>涨幅榜</span>
                        <div
                            onClick={() => setMode(mode === 'ranking' ? 'manual' : 'ranking')}
                            style={{
                                width: '48px',
                                height: '24px',
                                backgroundColor: mode === 'manual' ? '#667eea' : '#cbd5e1',
                                borderRadius: '12px',
                                position: 'relative',
                                cursor: 'pointer',
                                transition: 'background-color 0.3s',
                                flexShrink: 0
                            }}
                        >
                            <div style={{
                                width: '20px',
                                height: '20px',
                                backgroundColor: 'white',
                                borderRadius: '50%',
                                position: 'absolute',
                                top: '2px',
                                left: mode === 'manual' ? '26px' : '2px',
                                transition: 'left 0.3s',
                                boxShadow: '0 2px 4px rgba(0,0,0,0.2)'
                            }} />
                        </div>
                        <span style={{
                            fontSize: '14px',
                            color: mode === 'manual' ? '#667eea' : '#64748b',
                            fontWeight: mode === 'manual' ? '600' : '400',
                            transition: 'all 0.2s'
                        }}>手动选择</span>
                    </div>
                </div>

                {/* 根据模式显示不同的参数 */}
                {mode === 'ranking' ? (
                    <>
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
                    </>
                ) : (
                    <div className="param-group" style={{ gridColumn: '1 / -1' }}>
                        <label>选择币种</label>
                        <SymbolSelector
                            symbols={availableSymbols}
                            selectedSymbols={selectedSymbols}
                            onChange={setSelectedSymbols}
                            loadingSymbols={loadingSymbols}
                        />
                    </div>
                )}

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
                    <label>监控小时</label>
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
                    {/* 回溯模式提示 */}
                    {result.isBacktrackMode && (
                        <div className="backtrack-info">
                            ⏪ 回溯模式: 查看 <strong>{result.backtrackTime?.replace('T', ' ')}</strong> 时刻的持仓情况
                        </div>
                    )}

                    {/* 自动刷新控制 */}
                    <div style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'flex-end',
                        padding: '12px 0',
                        gap: '12px',
                        borderBottom: '1px solid #e2e8f0',
                        marginBottom: '16px'
                    }}>
                        <span style={{ fontSize: '14px', color: '#64748b' }}>
                            🔄 自动刷新
                        </span>
                        <div
                            onClick={() => setAutoRefresh(!autoRefresh)}
                            style={{
                                width: '48px',
                                height: '24px',
                                backgroundColor: autoRefresh ? '#10b981' : '#cbd5e1',
                                borderRadius: '12px',
                                position: 'relative',
                                cursor: 'pointer',
                                transition: 'background-color 0.3s',
                            }}
                        >
                            <div style={{
                                width: '20px',
                                height: '20px',
                                backgroundColor: 'white',
                                borderRadius: '50%',
                                position: 'absolute',
                                top: '2px',
                                left: autoRefresh ? '26px' : '2px',
                                transition: 'left 0.3s',
                                boxShadow: '0 2px 4px rgba(0,0,0,0.2)'
                            }} />
                        </div>
                        <input
                            type="number"
                            min="5"
                            max="300"
                            value={refreshInterval}
                            onChange={(e) => setRefreshInterval(e.target.value === '' ? '' : parseInt(e.target.value))}
                            onBlur={(e) => { if (e.target.value === '' || isNaN(refreshInterval) || refreshInterval < 5) setRefreshInterval(30) }}
                            style={{
                                width: '60px',
                                padding: '4px 8px',
                                border: '1px solid #e2e8f0',
                                borderRadius: '6px',
                                fontSize: '13px',
                                textAlign: 'center'
                            }}
                        />
                        <span style={{ fontSize: '13px', color: '#64748b' }}>秒</span>
                    </div>

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
                                        <button
                                            className="backtrack-btn-row"
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                handleBacktrackClick(hour.hour)
                                            }}
                                            title="回溯到该时间点"
                                        >
                                            ⏪ 回溯
                                        </button>
                                        <button
                                            className="price-index-btn"
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                handlePriceIndexClick(hour.hour)
                                            }}
                                            title="查看价格指数图"
                                        >
                                            📊 指数
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
                                                    <span
                                                        className="trade-symbol clickable-symbol"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            handleCopySymbol(trade.symbol);
                                                        }}
                                                        title={`点击复制 ${trade.symbol}`}
                                                    >
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
                                                                <span
                                                                    className="trade-symbol clickable-symbol"
                                                                    onClick={() => handleCopySymbol(trade.symbol)}
                                                                    title={`点击复制 ${trade.symbol}`}
                                                                >
                                                                    {trade.symbol.replace('USDT', '')}
                                                                </span>
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

            {/* 价格指数图侧边栏 */}
            {priceIndexData && createPortal(
                <>
                    <div className="sidebar-overlay" onClick={() => setPriceIndexData(null)} />
                    <div className={`sidebar-container wide ${priceIndexData ? 'open' : ''}`}>
                        <div className="sidebar-content-wrapper wide">
                            <div className="sidebar-header">
                                <div className="sidebar-title">
                                    📊 价格指数走势
                                    <div className="sidebar-subtitle">
                                        入场时间: <strong>{priceIndexData.entryTime}</strong> |
                                        币种: {priceIndexData.symbols?.slice(0, 3).map(s => s.replace('USDT', '')).join(', ')}
                                        {priceIndexData.symbols?.length > 3 && ` +${priceIndexData.symbols.length - 3}`}
                                    </div>
                                </div>
                                <button className="modal-close" onClick={() => setPriceIndexData(null)}>×</button>
                            </div>

                            <div className="sidebar-body">
                                {/* 颗粒度选择器 */}
                                <div className="granularity-selector">
                                    <span className="selector-label">颗粒度:</span>
                                    {[5, 15, 30, 60].map(g => (
                                        <button
                                            key={g}
                                            className={`granularity-btn ${priceIndexGranularity === g ? 'active' : ''}`}
                                            onClick={() => handlePriceIndexClick(priceIndexData.entryTime, g)}
                                        >
                                            {g === 60 ? '1小时' : `${g}分钟`}
                                        </button>
                                    ))}
                                </div>

                                {/* 价格指数图表 */}
                                <div className="sidebar-chart-container wide">
                                    <ResponsiveContainer width="100%" height={500}>
                                        <LineChart
                                            data={priceIndexData.priceIndexData}
                                            margin={{ top: 20, right: 30, left: 10, bottom: 60 }}
                                        >
                                            <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                                            <XAxis
                                                dataKey="time"
                                                tick={{ fontSize: 10, fill: '#64748b' }}
                                                angle={-35}
                                                textAnchor="end"
                                                height={50}
                                                tickFormatter={(value) => {
                                                    // 只有 00:00 才显示日期，其他只显示 HH:mm
                                                    const parts = value.split(' ');
                                                    if (parts.length >= 2) {
                                                        const timePart = parts[1];
                                                        const [hour, minute] = timePart.split(':');
                                                        // 只有午夜 00:00 显示日期+时间
                                                        if (hour === '00' && minute === '00') {
                                                            const datePart = parts[0].split('-').slice(1).join('/');
                                                            return `${datePart} 00:00`;
                                                        }
                                                        return `${hour}:${minute}`;
                                                    }
                                                    return value;
                                                }}
                                                interval={(() => {
                                                    // 根据数据点数量动态计算间隔
                                                    const dataLen = priceIndexData?.priceIndexData?.length || 0;
                                                    if (dataLen <= 20) return 0;  // 所有标签都显示
                                                    if (dataLen <= 50) return 1;  // 每2个显示1个
                                                    if (dataLen <= 100) return 3; // 每4个显示1个
                                                    return Math.floor(dataLen / 20); // 约显示20个标签
                                                })()}
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
                                                    value: '入场 (100)',
                                                    position: 'right',
                                                    fill: '#667eea',
                                                    fontSize: 11
                                                }}
                                            />
                                            <Line
                                                type="monotone"
                                                dataKey="priceIndex"
                                                stroke="url(#priceIndexGradient2)"
                                                strokeWidth={2.5}
                                                dot={(props) => {
                                                    const { cx, cy, payload } = props;
                                                    if (payload.isEntryPoint) {
                                                        return (
                                                            <g key={`entry-${payload.time}`}>
                                                                <circle cx={cx} cy={cy} r={10} fill="#ef4444" opacity={0.3} />
                                                                <circle cx={cx} cy={cy} r={6} fill="#ef4444" stroke="#fff" strokeWidth={2} />
                                                                <text x={cx} y={cy - 15} textAnchor="middle" fill="#ef4444" fontSize={11} fontWeight="bold">入场</text>
                                                            </g>
                                                        );
                                                    }
                                                    if (payload.isRealTime) {
                                                        return (
                                                            <g key={`realtime-${payload.time}`}>
                                                                {/* 呼吸灯动效背景 */}
                                                                <circle cx={cx} cy={cy} r={8} fill="#0ecb81" opacity={0.4}>
                                                                    <animate attributeName="r" values="6;10;6" dur="2s" repeatCount="indefinite" />
                                                                    <animate attributeName="opacity" values="0.4;0.1;0.4" dur="2s" repeatCount="indefinite" />
                                                                </circle>
                                                                <circle cx={cx} cy={cy} r={5} fill="#0ecb81" stroke="#fff" strokeWidth={2} />
                                                                <text x={cx} y={cy - 12} textAnchor="middle" fill="#0ecb81" fontSize={10} fontWeight="bold">最新</text>
                                                            </g>
                                                        );
                                                    }
                                                    return <circle key={`dot-${payload.time}`} cx={cx} cy={cy} r={2} fill="#667eea" />;
                                                }}
                                                activeDot={{ r: 5 }}
                                            />
                                            <defs>
                                                <linearGradient id="priceIndexGradient2" x1="0" y1="0" x2="1" y2="0">
                                                    <stop offset="0%" stopColor="#667eea" />
                                                    <stop offset="100%" stopColor="#764ba2" />
                                                </linearGradient>
                                            </defs>
                                        </LineChart>
                                    </ResponsiveContainer>
                                    <div style={{ textAlign: 'center', fontSize: '12px', color: '#64748b', marginTop: '8px' }}>
                                        📈 指数&gt;100 = 币价上涨(亏损方向) | 📉 指数&lt;100 = 币价下跌(盈利方向)
                                    </div>

                                    {/* 币种多周期涨幅表格 */}
                                    {priceIndexData.coinPerformance && priceIndexData.coinPerformance.length > 0 && (
                                        <div style={{ marginTop: '16px' }}>
                                            <div style={{ fontSize: '13px', fontWeight: 600, color: '#334155', marginBottom: '8px', paddingLeft: '4px' }}>
                                                📋 币种涨幅概览
                                            </div>
                                            <div style={{ overflowX: 'auto' }}>
                                                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '14px', lineHeight: '1.6' }}>
                                                    <thead>
                                                        <tr style={{ background: '#f8fafc', borderBottom: '2px solid #e2e8f0' }}>
                                                            <th style={{ padding: '10px 8px', textAlign: 'left', color: '#64748b', fontWeight: 600, whiteSpace: 'nowrap' }}>币种</th>
                                                            <th style={{ padding: '10px 8px', textAlign: 'right', color: '#64748b', fontWeight: 600, whiteSpace: 'nowrap' }}>当前价格</th>
                                                            <th style={{ padding: '10px 8px', textAlign: 'center', color: '#64748b', fontWeight: 600, whiteSpace: 'nowrap' }}>入场涨幅</th>
                                                            <th style={{ padding: '10px 8px', textAlign: 'center', color: '#64748b', fontWeight: 600, whiteSpace: 'nowrap' }}>1天 (当前/最高)</th>
                                                            <th style={{ padding: '10px 8px', textAlign: 'center', color: '#64748b', fontWeight: 600, whiteSpace: 'nowrap' }}>3天 (当前/最高)</th>
                                                            <th style={{ padding: '10px 8px', textAlign: 'center', color: '#64748b', fontWeight: 600, whiteSpace: 'nowrap' }}>7天 (当前/最高)</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody>
                                                        {priceIndexData.coinPerformance.map((coin, idx) => {
                                                            const formatChange = (val) => {
                                                                if (val === undefined || val === null) return '--';
                                                                return `${val >= 0 ? '+' : ''}${val.toFixed(2)}%`;
                                                            };
                                                            const changeColor = (val) => {
                                                                if (val === undefined || val === null) return '#94a3b8';
                                                                return val >= 0 ? '#10b981' : '#ef4444';
                                                            };
                                                            const formatPrice = (p) => {
                                                                if (!p) return '--';
                                                                if (p < 0.001) return p.toFixed(8);
                                                                if (p < 1) return p.toFixed(6);
                                                                if (p < 100) return p.toFixed(4);
                                                                return p.toFixed(2);
                                                            };
                                                            return (
                                                                <tr key={coin.symbol} style={{
                                                                    borderBottom: '1px solid #f1f5f9',
                                                                    background: idx % 2 === 0 ? '#fff' : '#fafbfc'
                                                                }}>
                                                                    <td style={{ padding: '9px 8px', fontWeight: 600, color: '#334155' }}>
                                                                        {coin.symbol.replace('USDT', '')}
                                                                    </td>
                                                                    <td style={{ padding: '9px 8px', textAlign: 'right', color: '#475569', fontFamily: 'monospace', fontSize: '15px' }}>
                                                                        {formatPrice(coin.currentPrice)}
                                                                    </td>
                                                                    <td style={{ padding: '9px 8px', textAlign: 'center', fontFamily: 'monospace', fontSize: '15px' }}>
                                                                        {coin.entryChange !== undefined ? (
                                                                            <span style={{
                                                                                color: coin.entryDirection === 'profit' ? '#10b981' : '#ef4444',
                                                                                fontWeight: 600
                                                                            }}>
                                                                                {coin.entryDirection === 'profit' ? '▼' : '▲'} +{coin.entryChange.toFixed(2)}%
                                                                            </span>
                                                                        ) : '--'}
                                                                    </td>
                                                                    <td style={{ padding: '9px 8px', textAlign: 'center', fontFamily: 'monospace', fontSize: '15px' }}>
                                                                        <span style={{ color: changeColor(coin['1dChange']) }}>{formatChange(coin['1dChange'])}</span>
                                                                        <span style={{ color: '#cbd5e1', margin: '0 2px' }}>/</span>
                                                                        <span style={{ color: changeColor(coin['1dMaxChange']) }}>{formatChange(coin['1dMaxChange'])}</span>
                                                                    </td>
                                                                    <td style={{ padding: '9px 8px', textAlign: 'center', fontFamily: 'monospace', fontSize: '15px' }}>
                                                                        <span style={{ color: changeColor(coin['3dChange']) }}>{formatChange(coin['3dChange'])}</span>
                                                                        <span style={{ color: '#cbd5e1', margin: '0 2px' }}>/</span>
                                                                        <span style={{ color: changeColor(coin['3dMaxChange']) }}>{formatChange(coin['3dMaxChange'])}</span>
                                                                    </td>
                                                                    <td style={{ padding: '9px 8px', textAlign: 'center', fontFamily: 'monospace', fontSize: '15px' }}>
                                                                        <span style={{ color: changeColor(coin['7dChange']) }}>{formatChange(coin['7dChange'])}</span>
                                                                        <span style={{ color: '#cbd5e1', margin: '0 2px' }}>/</span>
                                                                        <span style={{ color: changeColor(coin['7dMaxChange']) }}>{formatChange(coin['7dMaxChange'])}</span>
                                                                    </td>
                                                                </tr>
                                                            );
                                                        })}
                                                    </tbody>
                                                </table>
                                            </div>
                                        </div>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>
                </>,
                document.body
            )}
        </div>
    )
});

export default LiveMonitorModule
