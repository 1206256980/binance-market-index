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
         * 批量查询多个时间点的所有价格数据
         */
        @Query("SELECT k FROM HourlyKline k WHERE k.openTime IN :times")
        List<HourlyKline> findAllByOpenTimeIn(@Param("times") java.util.Collection<LocalDateTime> times);

        @Query("SELECT k.symbol, COUNT(k) FROM HourlyKline k WHERE k.openTime BETWEEN :startTime AND :endTime GROUP BY k.symbol")
        List<Object[]> countBySymbolInRange(
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

        /**
         * 高性能查询：仅查询回测所需的关键字段 (symbol, time, price)
         * 使用范围查询代替 IN 子句，并只读取需要的列以减少内存占用和IO
         */
        @Query("SELECT k.symbol, k.openTime, k.openPrice FROM HourlyKline k WHERE k.openTime BETWEEN :startTime AND :endTime")
        List<Object[]> findAllPartialByOpenTimeBetween(
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

        /**
         * 查找指定币种的所有K线（按时间降序）
         */
        List<HourlyKline> findBySymbolOrderByOpenTimeDesc(String symbol);

        /**
         * 查找指定币种的K线（分页，按时间降序）
         */
        List<HourlyKline> findBySymbolOrderByOpenTimeDesc(String symbol,
                        org.springframework.data.domain.Pageable pageable);
}
