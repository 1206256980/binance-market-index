package com.binance.index.dto;

import java.time.LocalDateTime;

/**
 * 轻量级价格数据对象，用于优化单边涨幅计算的内存占用
 */
public class CoinPriceDTO {
    private final String symbol;
    private final LocalDateTime timestamp;
    private final Double openPrice;
    private final Double highPrice;
    private final Double lowPrice;
    private final Double price; // 收盘价

    public CoinPriceDTO(String symbol, LocalDateTime timestamp, Double openPrice,
            Double highPrice, Double lowPrice, Double price) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.price = price;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public Double getOpenPrice() {
        return openPrice;
    }

    public Double getHighPrice() {
        return highPrice;
    }

    public Double getLowPrice() {
        return lowPrice;
    }

    public Double getPrice() {
        return price;
    }
}
