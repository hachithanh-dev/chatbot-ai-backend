package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.dto.response.admin.CrawlHistoryDto;
import dev.thanh.spring_ai.dto.response.admin.DashboardStatsResponse;
import dev.thanh.spring_ai.dto.response.admin.TopicCoverageDto;
import dev.thanh.spring_ai.enums.CrawlerPageStatus;
import dev.thanh.spring_ai.enums.DocumentStatus;
import dev.thanh.spring_ai.repository.CrawlerPageRepository;
import dev.thanh.spring_ai.repository.DocumentRepository;
import dev.thanh.spring_ai.repository.JobExecutionRepository;
import dev.thanh.spring_ai.repository.MessageRepository;
import io.qdrant.client.QdrantClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
public class AdminDashboardService {

    private final DocumentRepository documentRepository;
    private final CrawlerPageRepository crawlerPageRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final MessageRepository messageRepository;
    private final QdrantClient qdrantClient;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:spring}")
    private String collectionName;

    @Value("${crawler.default-target-chunks:1000}")
    private int defaultTargetChunks;

    /**
     * Aggregates dashboard stats from DB + Qdrant.
     *
     * <p><b>Connection optimization:</b> The Qdrant gRPC call (timeout 5s) is
     * separated OUTSIDE the {@code @Transactional} scope. The DB connection is
     * only held during {@link #queryDbStats()} (~2-5ms), then released back to
     * the pool BEFORE calling Qdrant.</p>
     *
     * <p>Combined with {@code LazyConnectionDataSourceProxy}, the flow becomes:</p>
     * <ol>
     *   <li>{@code queryDbStats()} → acquire connection → execute 3 COUNT queries → release</li>
     *   <li>{@code countQdrantVectors()} → gRPC call (NO DB connection held)</li>
     *   <li>Build response from both results</li>
     * </ol>
     */
    public DashboardStatsResponse getStats() {
        // Phase 1: DB queries — connection is only held within this scope
        DbStats dbStats = queryDbStats();

        // Phase 2: Qdrant gRPC — NO DB connection held
        long qdrantVectors = countQdrantVectors();

        // TODO: Default low relevance percent until calculation logic is implemented
        double lowRelevancePercent = 5.0;

        return new DashboardStatsResponse(
                dbStats.activeDocs(),
                qdrantVectors,
                dbStats.pendingPages(),
                dbStats.lastCrawlInfo(),
                dbStats.todayQueries(),
                lowRelevancePercent
        );
    }

    /**
     * Groups all DB queries into a single readOnly transaction.
     * Connection is acquired (lazily) and released within the smallest scope.
     */
    @Transactional(readOnly = true)
    DbStats queryDbStats() {
        long activeDocs = documentRepository.countByStatus(DocumentStatus.ACTIVE);
        long pendingPages = crawlerPageRepository.countByStatus(CrawlerPageStatus.PENDING);
        long todayQueries = messageRepository.countTodayUserMessages(LocalDate.now().atStartOfDay());

        DashboardStatsResponse.LastCrawlInfo lastCrawlInfo = jobExecutionRepository
                .findTopByJobIdOrderByStartedAtDesc("crawl-all")
                .map(job -> new DashboardStatsResponse.LastCrawlInfo(
                        job.getPagesCount() != null ? job.getPagesCount() : 0,
                        job.getFinishedAt() != null ? job.getFinishedAt() : job.getStartedAt()
                ))
                .orElse(null);

        return new DbStats(activeDocs, pendingPages, todayQueries, lastCrawlInfo);
    }

    private long countQdrantVectors() {
        try {
            return qdrantClient.countAsync(collectionName)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to get Qdrant vector count", e);
            return 0L;
        }
    }

    /**
     * Internal record to aggregate DB query results for passing between methods.
     */
    record DbStats(
            long activeDocs,
            long pendingPages,
            long todayQueries,
            DashboardStatsResponse.LastCrawlInfo lastCrawlInfo
    ) {}

    @Transactional(readOnly = true)
    public List<TopicCoverageDto> getTopicCoverage() {
        List<Object[]> rawSums = documentRepository.sumChunksByTopic();

        return rawSums.stream().map(row -> {
            String topic = (String) row[0];
            int chunkCount = ((Number) row[1]).intValue();
            
            // Target chunks are typically retrieved from a mapping; fall back to default
            // Temporarily set higher target for "spring" topics
            int targetChunks = topic != null && topic.contains("spring") ? 2000 : defaultTargetChunks;
            
            double coverage = Math.min(100.0, (chunkCount * 100.0) / targetChunks);
            boolean warning = coverage < 40.0;

            return new TopicCoverageDto(
                    topic == null ? "Uncategorized" : topic,
                    chunkCount,
                    targetChunks,
                    Math.round(coverage * 100.0) / 100.0,
                    warning
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<CrawlHistoryDto> getCrawlHistory() {
        LocalDateTime since = LocalDateTime.now().minusDays(8);
        List<Object[]> history = jobExecutionRepository.findDailyHistory(since);

        return history.stream().map(row -> {
            // row[0] is Date or Timestamp based on dialect, cast appropriately
            java.util.Date sqlDate = (java.util.Date) row[0];
            LocalDate date = new java.sql.Date(sqlDate.getTime()).toLocalDate();
            int success = ((Number) row[1]).intValue();
            int fail = ((Number) row[2]).intValue();

            return new CrawlHistoryDto(date, success, fail);
        }).toList();
    }
}
