package com.binance.index.repository;

import com.binance.index.entity.HourlyKline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HourlyKlineRepository extends JpaRepository<HourlyKline, Long> {

    /**
     * 查找指定币种在指定时间点的K线
     */
    Optional<HourlyKline> findBySymbolAndOpenTime(String symbol, LocalDateTime openTime);

    /**
     * 查找指定币种在时间范围内的所有K线
     */
    List<HourlyKline> findBySymbolAndOpenTimeBetweenOrderByOpenTime(
            String symbol, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 查找指定时间点所有币种的K线
     */
    List<HourlyKline> findByOpenTime(LocalDateTime openTime);

    /**
     * 检查指定币种在指定时间范围内是否有数据
     */
    @Query("SELECT COUNT(k) FROM HourlyKline k WHERE k.symbol = :symbol AND k.openTime BETWEEN :startTime AND :endTime")
    long countBySymbolAndTimeRange(
            @Param("symbol") String symbol,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 批量查询多个币种在指定时间点的收盘价
     */
    @Query("SELECT k FROM HourlyKline k WHERE k.openTime = :openTime")
    List<HourlyKline> findAllByOpenTime(@Param("openTime") LocalDateTime openTime);

    /**
     * 查找最接近目标时间的K线（往前找）
     */
    @Query("SELECT k FROM HourlyKline k WHERE k.openTime <= :targetTime ORDER BY k.openTime DESC")
    List<HourlyKline> findLatestBefore(@Param("targetTime") LocalDateTime targetTime);
}
