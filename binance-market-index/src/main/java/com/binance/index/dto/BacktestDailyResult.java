package com.binance.index.dto;

import java.util.List;

/**
 * 回测每日结果
 */
public class BacktestDailyResult {
    private String date; // 日期 yyyy-MM-dd
    private String entryTime; // 入场时间
    private String exitTime; // 平仓时间
    private Double totalProfit; // 当日总盈亏
    private Integer winCount; // 盈利笔数
    private Integer loseCount; // 亏损笔数
    private List<BacktestTrade> trades; // 交易明细

    public BacktestDailyResult() {
    }

    // Getters and Setters
    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getEntryTime() {
        return entryTime;
    }

    public void setEntryTime(String entryTime) {
        this.entryTime = entryTime;
    }

    public String getExitTime() {
        return exitTime;
    }

    public void setExitTime(String exitTime) {
        this.exitTime = exitTime;
    }

    public Double getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(Double totalProfit) {
        this.totalProfit = totalProfit;
    }

    public Integer getWinCount() {
        return winCount;
    }

    public void setWinCount(Integer winCount) {
        this.winCount = winCount;
    }

    public Integer getLoseCount() {
        return loseCount;
    }

    public void setLoseCount(Integer loseCount) {
        this.loseCount = loseCount;
    }

    public List<BacktestTrade> getTrades() {
        return trades;
    }

    public void setTrades(List<BacktestTrade> trades) {
        this.trades = trades;
    }
}
