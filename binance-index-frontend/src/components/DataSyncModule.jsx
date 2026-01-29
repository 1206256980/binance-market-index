import React, { useState } from 'react'
import axios from 'axios'

/**
 * 数据同步模块 - 专门用于手动拉取币安K线数据到本地数据库
 */
function DataSyncModule() {
    const [days, setDays] = useState(30)
    const [loading, setLoading] = useState(false)
    const [message, setMessage] = useState(null)
    const [error, setError] = useState(null)

    const handleSync = async () => {
        setLoading(true)
        setMessage(null)
        setError(null)

        try {
            const res = await axios.post(`/api/index/backtest/sync-data?days=${days}`)
            if (res.data.success) {
                setMessage(res.data.message)
            } else {
                setError(res.data.message || '同步启动失败')
            }
        } catch (err) {
            console.error('同步请求失败:', err)
            setError(err.response?.data?.message || err.message || '请求失败')
        } finally {
            setLoading(false)
        }
    }

    return (
        <div className="data-sync-module">
            <div className="sync-card">
                <div className="sync-header">
                    <div className="sync-title">🔄 数据同步工具</div>
                    <div className="sync-subtitle">手动补全本地 K 线数据库，提升回测速度</div>
                </div>

                <div className="sync-controls">
                    <div className="param-group">
                        <label>同步天数 (从今天往前推):</label>
                        <input
                            type="number"
                            value={days}
                            onChange={(e) => setDays(parseInt(e.target.value))}
                            min="1"
                            max="365"
                        />
                    </div>

                    <button
                        className={`sync-btn ${loading ? 'loading' : ''}`}
                        onClick={handleSync}
                        disabled={loading}
                    >
                        {loading ? '任务已启动...' : '🚀 开始同步'}
                    </button>
                </div>

                {message && <div className="sync-success">✅ {message}</div>}
                {error && <div className="sync-error">⚠️ {error}</div>}

                <div className="sync-tip">
                    温馨提示：同步任务在后台运行，您可以继续进行其他操作。建议回测前先同步所需天数的数据。
                </div>
            </div>
        </div>
    )
}

export default DataSyncModule
