package com.binance.index.dto;

import java.util.List;

/**
 * 回测总体结果
 */
public class BacktestResult {

    // 总体统计
    private Integer totalDays; // 回测总天数
    private Integer validDays; // 有效天数（有数据的天数）
    private Integer totalTrades; // 总交易笔数
    private Integer winTrades; // 盈利笔数
    private Integer loseTrades; // 亏损笔数
    private Double winRate; // 胜率(%)
    private Double totalProfit; // 总盈亏(U)

    // 每日明细
    private List<BacktestDailyResult> dailyResults;

    // 跳过的日期（无数据）
    private List<String> skippedDays;

    public BacktestResult() {
    }

    // Getters and Setters
    public Integer getTotalDays() {
        return totalDays;
    }

    public void setTotalDays(Integer totalDays) {
        this.totalDays = totalDays;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public void setValidDays(Integer validDays) {
        this.validDays = validDays;
    }

    public Integer getTotalTrades() {
        return totalTrades;
    }

    public void setTotalTrades(Integer totalTrades) {
        this.totalTrades = totalTrades;
    }

    public Integer getWinTrades() {
        return winTrades;
    }

    public void setWinTrades(Integer winTrades) {
        this.winTrades = winTrades;
    }

    public Integer getLoseTrades() {
        return loseTrades;
    }

    public void setLoseTrades(Integer loseTrades) {
        this.loseTrades = loseTrades;
    }

    public Double getWinRate() {
        return winRate;
    }

    public void setWinRate(Double winRate) {
        this.winRate = winRate;
    }

    public Double getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(Double totalProfit) {
        this.totalProfit = totalProfit;
    }

    public List<BacktestDailyResult> getDailyResults() {
        return dailyResults;
    }

    public void setDailyResults(List<BacktestDailyResult> dailyResults) {
        this.dailyResults = dailyResults;
    }

    public List<String> getSkippedDays() {
        return skippedDays;
    }

    public void setSkippedDays(List<String> skippedDays) {
        this.skippedDays = skippedDays;
    }
}
