package com.binance.index.repository;

import com.binance.index.entity.MarketIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketIndexRepository extends JpaRepository<MarketIndex, Long> {

    /**
     * 查找指定时间范围内的指数数据
     */
    List<MarketIndex> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime start, LocalDateTime end);

    /**
     * 查找指定时间之后的所有数据
     */
    List<MarketIndex> findByTimestampAfterOrderByTimestampAsc(LocalDateTime timestamp);

    /**
     * 获取最新的指数记录
     */
    Optional<MarketIndex> findTopByOrderByTimestampDesc();

    /**
     * 检查某个时间点是否已有数据
     */
    boolean existsByTimestamp(LocalDateTime timestamp);

    /**
     * 获取最早的记录
     */
    Optional<MarketIndex> findTopByOrderByTimestampAsc();

    /**
     * 删除指定时间之前的数据（用于清理旧数据）
     */
    void deleteByTimestampBefore(LocalDateTime timestamp);

    /**
     * 统计指定时间范围内的记录数
     */
    long countByTimestampBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 批量查询时间范围内所有已存在的时间戳（用于优化回补时的存在性检查）
     */
    @Query("SELECT m.timestamp FROM MarketIndex m WHERE m.timestamp >= :startTime AND m.timestamp <= :endTime")
    List<LocalDateTime> findAllTimestampsBetween(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}
