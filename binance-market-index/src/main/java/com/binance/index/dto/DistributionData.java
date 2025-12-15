package com.binance.index.dto;

import java.util.List;

/**
 * 涨幅分布响应数据
 */
public class DistributionData {
    
    private long timestamp;                    // 时间戳
    private int totalCoins;                    // 总币种数
    private int upCount;                       // 上涨币种数
    private int downCount;                     // 下跌币种数
    private List<DistributionBucket> distribution; // 分布数据
    
    public DistributionData() {}
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public int getTotalCoins() {
        return totalCoins;
    }
    
    public void setTotalCoins(int totalCoins) {
        this.totalCoins = totalCoins;
    }
    
    public int getUpCount() {
        return upCount;
    }
    
    public void setUpCount(int upCount) {
        this.upCount = upCount;
    }
    
    public int getDownCount() {
        return downCount;
    }
    
    public void setDownCount(int downCount) {
        this.downCount = downCount;
    }
    
    public List<DistributionBucket> getDistribution() {
        return distribution;
    }
    
    public void setDistribution(List<DistributionBucket> distribution) {
        this.distribution = distribution;
    }
}
