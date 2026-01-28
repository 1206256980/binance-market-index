package com.binance.index.dto;

/**
 * 月度回测明细
 */
public class BacktestMonthlyResult {
    private String monthLabel; // 月份标识，如 "第 1 个月" 或日期范围
    private Double totalProfit; // 该月总盈亏
    private Integer winDays; // 该月盈利天数
    private Integer loseDays; // 该月亏损天数

    public BacktestMonthlyResult() {
    }

    public String getMonthLabel() {
        return monthLabel;
    }

    public void setMonthLabel(String monthLabel) {
        this.monthLabel = monthLabel;
    }

    public Double getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(Double totalProfit) {
        this.totalProfit = totalProfit;
    }

    public Integer getWinDays() {
        return winDays;
    }

    public void setWinDays(Integer winDays) {
        this.winDays = winDays;
    }

    public Integer getLoseDays() {
        return loseDays;
    }

    public void setLoseDays(Integer loseDays) {
        this.loseDays = loseDays;
    }
}
