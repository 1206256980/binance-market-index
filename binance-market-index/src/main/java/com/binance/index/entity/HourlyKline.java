package com.binance.index.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 小时K线数据表 (hourly_kline) - 用于回测的历史价格缓存
 * 存储每小时的开高低收价格和成交量
 * 数据来源：币安 API 历史 K 线
 */
@Entity
@Table(name = "hourly_kline", indexes = {
        @Index(name = "idx_hourly_kline_symbol_time", columnList = "symbol, open_time"),
        @Index(name = "idx_hourly_kline_time", columnList = "open_time")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_hourly_kline_symbol_time", columnNames = { "symbol", "open_time" })
})
public class HourlyKline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "open_time", nullable = false)
    private LocalDateTime openTime;

    @Column(name = "open_price", nullable = false)
    private Double openPrice;

    @Column(name = "high_price", nullable = false)
    private Double highPrice;

    @Column(name = "low_price", nullable = false)
    private Double lowPrice;

    @Column(name = "close_price", nullable = false)
    private Double closePrice;

    @Column
    private Double volume;

    public HourlyKline() {
    }

    public HourlyKline(String symbol, LocalDateTime openTime, Double openPrice,
            Double highPrice, Double lowPrice, Double closePrice, Double volume) {
        this.symbol = symbol;
        this.openTime = openTime;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public LocalDateTime getOpenTime() {
        return openTime;
    }

    public void setOpenTime(LocalDateTime openTime) {
        this.openTime = openTime;
    }

    public Double getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(Double openPrice) {
        this.openPrice = openPrice;
    }

    public Double getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(Double highPrice) {
        this.highPrice = highPrice;
    }

    public Double getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(Double lowPrice) {
        this.lowPrice = lowPrice;
    }

    public Double getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(Double closePrice) {
        this.closePrice = closePrice;
    }

    public Double getVolume() {
        return volume;
    }

    public void setVolume(Double volume) {
        this.volume = volume;
    }
}
