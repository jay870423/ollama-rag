package com.example.rag.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import com.example.rag.client.OllamaClient;
import com.example.rag.model.Document;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

// Guava缓存相关导入
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * 作者: liangyajie
 * 联系方式: 695274107@qq.com
 * 简单向量存储实现，用于文档嵌入和相似度检索
 */

@Component
public class SimpleVectorStore {

    private final OllamaClient ollamaClient;
    private final String embeddingModel;
    private final List<Document> documents = Collections.synchronizedList(new ArrayList<>());
    // 查询缓存，使用LRU缓存策略
    private final LoadingCache<String, List<Document>> queryCache;
    // 线程池用于并行处理
    private final ExecutorService executorService;
    // 文档按文件ID分组，提高多文件查询效率
    private final Map<String, List<Document>> documentsByFileId = new ConcurrentHashMap<>();

    @Autowired
    public SimpleVectorStore(OllamaClient ollamaClient, 
                           @Value("${ollama.embedding-model:mxbai-embed-large}") String embeddingModel) {
        this.ollamaClient = ollamaClient;
        this.embeddingModel = embeddingModel;
        
        // 初始化查询缓存，最多缓存100个查询结果，过期时间5分钟
        this.queryCache = CacheBuilder.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build(new CacheLoader<String, List<Document>>() {
                    @Override
                    public List<Document> load(String query) throws Exception {
                        return performSimilaritySearch(query, 10);
                    }
                });
        
        // 初始化线程池，线程数根据CPU核心数调整
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.executorService = Executors.newFixedThreadPool(corePoolSize);
    }
    
    // 使用@PreDestroy注解确保在Spring容器关闭时清理资源
    @PreDestroy
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
        }
    }

    // 添加文档到向量存储
    public void add(List<Document> newDocuments) {
        try {
            // 并行生成嵌入向量
            List<Future<Document>> futures = newDocuments.stream()
                .map(doc -> executorService.submit(() -> {
                    try {
                        List<Double> embedding = ollamaClient.generateEmbedding(embeddingModel, doc.getContent());
                        doc.setEmbedding(embedding);
                        return doc;
                    } catch (Exception e) {
                        System.err.println("Error generating embedding: " + e.getMessage());
                        return null;
                    }
                }))
                .collect(Collectors.toList());
            
            // 收集结果并更新存储
            for (Future<Document> future : futures) {
                try {
                    Document doc = future.get();
                    if (doc != null && doc.getEmbedding() != null) {
                        documents.add(doc);
                        
                        // 按文件ID分组存储
                        if (doc.getMetadata() != null && doc.getMetadata().containsKey("fileId")) {
                            String fileId = (String) doc.getMetadata().get("fileId");
                            documentsByFileId.computeIfAbsent(fileId, k -> new ArrayList<>()).add(doc);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error processing document: " + e.getMessage());
                }
            }
            
            // 清除查询缓存，因为数据已更新
            queryCache.invalidateAll();
        } catch (Exception e) {
            System.err.println("Error in batch document processing: " + e.getMessage());
        }
    }

    // 根据相似度搜索文档，使用缓存和并行处理优化性能
    public List<Document> similaritySearch(String query, int topK) {
        try {
            // 生成缓存键，包含查询内容和topK值
            String cacheKey = query + "_" + topK;
            
            // 尝试从缓存获取结果
            try {
                return queryCache.get(cacheKey).stream()
                        .limit(topK)
                        .collect(Collectors.toList());
            } catch (ExecutionException e) {
                // 缓存加载失败，执行实际搜索
                return performSimilaritySearch(query, topK);
            }
        } catch (Exception e) {
            System.err.println("Error during similarity search: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    // 执行实际的相似度搜索（供缓存加载器使用）
    private List<Document> performSimilaritySearch(String query, int topK) {
        try {
            // 使用Ollama为查询生成嵌入向量
            List<Double> queryEmbedding = ollamaClient.generateEmbedding(embeddingModel, query);
            
            // 并行计算文档相似度
            List<Future<DocumentWithScore>> futureScores = documents.stream()
                .filter(doc -> doc.getEmbedding() != null && !doc.getEmbedding().isEmpty())
                .map(doc -> executorService.submit(() -> {
                    double score = cosineSimilarity(queryEmbedding, doc.getEmbedding());
                    return new DocumentWithScore(doc, score);
                }))
                .collect(Collectors.toList());
            
            // 收集相似度计算结果
            List<DocumentWithScore> scoredDocuments = new ArrayList<>();
            for (Future<DocumentWithScore> future : futureScores) {
                try {
                    DocumentWithScore scoredDoc = future.get();
                    // 只保留相似度大于阈值的文档，减少后续处理量
                    if (scoredDoc.getScore() > 0.5) {
                        scoredDocuments.add(scoredDoc);
                    }
                } catch (Exception e) {
                    System.err.println("Error collecting similarity score: " + e.getMessage());
                }
            }
            
            // 快速排序相似度分数
            scoredDocuments.sort(Comparator.comparing(DocumentWithScore::getScore).reversed());
            
            // 优化：确保从多个文件中获取文档，同时保持高相关性
            List<Document> resultDocs = new ArrayList<>();
            Set<String> includedFileIds = new HashSet<>();
            
            // 第一阶段：优先选择高相关性文档，同时确保覆盖多个文件
            for (DocumentWithScore scoredDoc : scoredDocuments) {
                if (resultDocs.size() >= topK) break;
                
                Document doc = scoredDoc.getDocument();
                String fileId = null;
                
                // 获取文档的文件ID（如果有）
                if (doc.getMetadata() != null && doc.getMetadata().containsKey("fileId")) {
                    fileId = (String) doc.getMetadata().get("fileId");
                }
                
                // 如果文档相关性足够高，或者来自新文件，添加到结果中
                if (scoredDoc.getScore() > 0.7 || fileId == null || !includedFileIds.contains(fileId)) {
                    resultDocs.add(doc);
                    if (fileId != null) {
                        includedFileIds.add(fileId);
                    }
                }
            }
            
            // 第二阶段：如果结果不足topK，添加剩余高相关性文档
            if (resultDocs.size() < topK) {
                for (DocumentWithScore scoredDoc : scoredDocuments) {
                    if (resultDocs.size() >= topK) break;
                    
                    Document doc = scoredDoc.getDocument();
                    if (!resultDocs.contains(doc)) {
                        resultDocs.add(doc);
                    }
                }
            }
            
            return resultDocs;
        } catch (Exception e) {
            System.err.println("Error during similarity search execution: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // 清除所有文档
    public void deleteAll() {
        documents.clear();
        documentsByFileId.clear();
        queryCache.invalidateAll();
    }
    
    // 根据文件ID删除文档，优化为使用映射表快速删除
    public boolean deleteByFileId(String fileId) {
        // 先从分组映射中删除
        List<Document> fileDocs = documentsByFileId.remove(fileId);
        if (fileDocs != null) {
            // 从主文档列表中删除
            documents.removeAll(fileDocs);
            // 清除缓存
            queryCache.invalidateAll();
            return true;
        }
        
        // 备用方法：如果映射表中没有找到，执行原始的删除逻辑
        int initialSize = documents.size();
        documents.removeIf(doc -> doc.getMetadata() != null && 
                               fileId.equals(doc.getMetadata().get("fileId")));
        
        boolean removed = documents.size() < initialSize;
        if (removed) {
            queryCache.invalidateAll();
        }
        return removed;
    }
    
    // 获取所有文件映射（用于启动时恢复文件列表）
    public Map<String, String> getAllFileMappings() {
        Map<String, String> mappings = new HashMap<>();
        for (Document doc : documents) {
            if (doc.getMetadata() != null && 
                doc.getMetadata().containsKey("fileId") && 
                doc.getMetadata().containsKey("fileName")) {
                String fileId = (String) doc.getMetadata().get("fileId");
                String fileName = (String) doc.getMetadata().get("fileName");
                mappings.putIfAbsent(fileId, fileName);
            }
        }
        return mappings;
    }
    
    // 获取所有文件的详细信息，包括文件大小等
    public Map<String, Map<String, Object>> getAllFilesDetails() {
        Map<String, Map<String, Object>> fileDetailsMap = new HashMap<>();
        
        for (Document doc : documents) {
            if (doc.getMetadata() != null && doc.getMetadata().containsKey("fileId")) {
                String fileId = (String) doc.getMetadata().get("fileId");
                
                // 如果还没有为这个文件创建详细信息映射，则创建一个
                if (!fileDetailsMap.containsKey(fileId)) {
                    Map<String, Object> fileDetails = new HashMap<>();
                    fileDetails.put("fileId", fileId);
                    
                    // 从metadata中获取所有可用信息
                    if (doc.getMetadata().containsKey("fileName")) {
                        fileDetails.put("fileName", doc.getMetadata().get("fileName"));
                    }
                    if (doc.getMetadata().containsKey("fileSize")) {
                        fileDetails.put("fileSize", doc.getMetadata().get("fileSize"));
                    }
                    // 添加上传时间（使用文档添加时间或当前时间）
                    fileDetails.put("uploadedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date()));
                    
                    fileDetailsMap.put(fileId, fileDetails);
                }
            }
        }
        
        return fileDetailsMap;
    }

    // 辅助类：带相似度分数的文档
    private static class DocumentWithScore {
        private final Document document;
        private final double score;

        public DocumentWithScore(Document document, double score) {
            this.document = document;
            this.score = score;
        }

        public Document getDocument() {
            return document;
        }

        public double getScore() {
            return score;
        }
    }

    // 优化的余弦相似度计算方法
    private double cosineSimilarity(List<Double> vec1, List<Double> vec2) {
        // 快速路径：检查输入有效性
        if (vec1 == null || vec2 == null || vec1.isEmpty() || vec2.isEmpty()) {
            return 0.0;
        }
        
        // 获取向量长度，只处理到较短向量的长度
        final int vec1Size = vec1.size();
        final int vec2Size = vec2.size();
        final int length = Math.min(vec1Size, vec2Size);
        
        // 预计算循环边界，减少循环内的开销
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        // 优化的循环计算，避免循环内的函数调用和边界检查
        for (int i = 0; i < length; i++) {
            final double val1 = vec1.get(i);
            final double val2 = vec2.get(i);
            
            // 累加计算点积和向量范数
            dotProduct += val1 * val2;
            norm1 += val1 * val1;
            norm2 += val2 * val2;
        }
        
        // 避免除以零和不必要的计算
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        // 一次性计算平方根，减少Math.sqrt调用次数
        final double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
        
        // 计算最终相似度
        return dotProduct / denominator;
    }
}