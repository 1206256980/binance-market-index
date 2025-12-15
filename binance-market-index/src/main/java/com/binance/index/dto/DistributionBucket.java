package com.binance.index.dto;

import java.util.List;

/**
 * 涨幅分布区间数据
 */
public class DistributionBucket {
    
    private String range;      // 区间名称，如 "-10%~-5%"
    private int count;         // 该区间币种数量
    private List<String> coins; // 该区间的币种列表
    
    public DistributionBucket() {}
    
    public DistributionBucket(String range, int count, List<String> coins) {
        this.range = range;
        this.count = count;
        this.coins = coins;
    }
    
    public String getRange() {
        return range;
    }
    
    public void setRange(String range) {
        this.range = range;
    }
    
    public int getCount() {
        return count;
    }
    
    public void setCount(int count) {
        this.count = count;
    }
    
    public List<String> getCoins() {
        return coins;
    }
    
    public void setCoins(List<String> coins) {
        this.coins = coins;
    }
}
