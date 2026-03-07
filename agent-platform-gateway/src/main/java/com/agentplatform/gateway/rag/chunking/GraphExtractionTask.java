package com.agentplatform.gateway.rag.chunking;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图关系提取异步任务
 * 将图关系提取从主流程中分离，避免阻塞文档存储
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GraphExtractionTask {

    private final GraphExtractionService graphExtractionService;

    /**
     * 异步执行图关系提取
     * 
     * @param identity 调用者身份
     * @param collection 集合名称
     * @param chunks 分块列表
     * @param profile Chunk Profile
     * @param mode 提取模式
     */
    @Async("graphExtractionExecutor")
    public void extractRelationsAsync(CallerIdentity identity, String collection,
                                     List<Chunk> chunks, ChunkProfile profile,
                                     GraphExtractionMode mode) {
        try {
            log.info("Starting async graph extraction for collection: {}, mode: {}", collection, mode);
            graphExtractionService.extractAndStoreRelations(identity, collection, chunks, profile, mode);
            log.info("Completed async graph extraction for collection: {}", collection);
        } catch (Exception e) {
            log.error("Async graph extraction failed for collection: {}", collection, e);
        }
    }
}
