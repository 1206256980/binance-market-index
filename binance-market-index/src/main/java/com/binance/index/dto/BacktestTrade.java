package com.binance.index.dto;

/**
 * 回测单笔交易详情
 */
public class BacktestTrade {
    private String symbol; // 交易对
    private Double entryPrice; // 开仓价格
    private Double exitPrice; // 平仓价格
    private Double change24h; // 入场时的24小时涨幅(%)
    private Double profit; // 盈亏金额(U)
    private Double profitPercent; // 盈亏百分比(%)

    public BacktestTrade() {
    }

    public BacktestTrade(String symbol, Double entryPrice, Double exitPrice,
            Double change24h, Double profit, Double profitPercent) {
        this.symbol = symbol;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.change24h = change24h;
        this.profit = profit;
        this.profitPercent = profitPercent;
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(Double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public Double getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(Double exitPrice) {
        this.exitPrice = exitPrice;
    }

    public Double getChange24h() {
        return change24h;
    }

    public void setChange24h(Double change24h) {
        this.change24h = change24h;
    }

    public Double getProfit() {
        return profit;
    }

    public void setProfit(Double profit) {
        this.profit = profit;
    }

    public Double getProfitPercent() {
        return profitPercent;
    }

    public void setProfitPercent(Double profitPercent) {
        this.profitPercent = profitPercent;
    }
}
