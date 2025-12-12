package com.binance.index.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * API响应DTO - 单个指数数据点
 */
public class IndexDataPoint {
    private Long timestamp;  // 毫秒时间戳 (UTC)
    private Double indexValue;
    private Double totalVolume;
    private Integer coinCount;

    public IndexDataPoint() {}

    public IndexDataPoint(LocalDateTime time, Double indexValue, Double totalVolume, Integer coinCount) {
        // 将LocalDateTime(UTC)转换为UTC毫秒时间戳
        // 因为我们存储的LocalDateTime是UTC时间，所以用UTC偏移量转换
        this.timestamp = time.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.indexValue = indexValue;
        this.totalVolume = totalVolume;
        this.coinCount = coinCount;
    }

    // Getters and Setters
    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Double getIndexValue() {
        return indexValue;
    }

    public void setIndexValue(Double indexValue) {
        this.indexValue = indexValue;
    }

    public Double getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(Double totalVolume) {
        this.totalVolume = totalVolume;
    }

    public Integer getCoinCount() {
        return coinCount;
    }

    public void setCoinCount(Integer coinCount) {
        this.coinCount = coinCount;
    }
}
