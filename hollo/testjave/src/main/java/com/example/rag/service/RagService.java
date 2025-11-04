package com.example.rag.service;

import com.example.rag.client.OllamaClient;
import com.example.rag.model.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 作者: liangyajie
 * 联系方式: 695274107@qq.com
 * RAG服务类，处理文档上传、查询和向量检索
 */

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private final SimpleVectorStore vectorStore;
    private final OllamaClient ollamaClient;
    private final Tika tika;
    private final String model;
    private final ObjectMapper objectMapper;
    
    // 用于存储文件ID和文件名的映射关系
    private final Map<String, String> fileMappings = new ConcurrentHashMap<>();

    @Autowired
    public RagService(SimpleVectorStore vectorStore, OllamaClient ollamaClient, Tika tika,
                     @Value("${spring.ai.openai.chat.options.model:qwen2.5:1.5b}") String model,
                     ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.ollamaClient = ollamaClient;
        this.tika = tika;
        this.model = model;
        this.objectMapper = objectMapper;
        this.fileMappings.putAll(vectorStore.getAllFileMappings());
    }

    // 添加文档到向量存储
    public void addDocument(File file) throws IOException, org.apache.tika.exception.TikaException {
        logger.info("开始解析文件: {}", file.getName());
        // 使用Tika解析文档内容
        String content = tika.parseToString(file);
        logger.info("文件解析完成，内容长度: {} 字符", content.length());
        logger.debug("解析内容前100个字符: {}", content.length() > 100 ? content.substring(0, 100) : content);
        
        // 将文档分割成块（简单实现）
        List<String> chunks = splitDocumentIntoChunks(content, 1000, 200);
        
        // 生成文件ID
        String fileId = "file-" + UUID.randomUUID().toString();
        
        // 创建Document对象并添加到向量存储，在metadata中存储文件ID
        List<Document> documents = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("fileId", fileId);
                    metadata.put("fileName", file.getName());
                    metadata.put("fileSize", file.length());
                    return new Document(chunk, metadata);
                })
                .collect(Collectors.toList());
        
        vectorStore.add(documents);
        
        // 保存文件ID和文件名的映射，以便后续查询
        saveFileMapping(fileId, file.getName());
    }

    // 从向量存储中检索相关文档
    public List<Document> search(String query, int topK) {
        return vectorStore.similaritySearch(query, topK);
    }

    // 执行RAG查询，生成回答（非流式）
    public String ragQuery(String query) {
        try {
            // 1. 从向量存储中检索相关文档，增加获取的文档数量以确保覆盖多文件内容
            List<Document> relevantDocs = vectorStore.similaritySearch(query, 10);
            
            // 2. 构建提示，在每个文档块中嵌入来源信息，并收集实际相关的文档来源
            // 使用更严格的过滤和排序，确保只使用最相关的文档
            List<Document> filteredDocs = relevantDocs.stream()
                    .filter(doc -> {
                        // 检查文档嵌入向量是否存在且有效
                        return doc.getEmbedding() != null && !doc.getEmbedding().isEmpty() && 
                               doc.getContent() != null && !doc.getContent().trim().isEmpty();
                    })
                    .limit(5)  // 只使用最相关的前5个文档
                    .collect(Collectors.toList());
            
            // 构建上下文并收集实际使用的文档来源
            StringBuilder contextBuilder = new StringBuilder();
            Map<String, Document> sourceDocs = new LinkedHashMap<>();
            
            for (Document doc : filteredDocs) {
                String fileName = "未知来源";
                if (doc.getMetadata() != null && doc.getMetadata().containsKey("fileName")) {
                    fileName = (String) doc.getMetadata().get("fileName");
                    // 记录实际使用的来源文档
                    sourceDocs.put(fileName, doc);
                }
                contextBuilder.append("[SOURCE: " + fileName + "]\n");
                contextBuilder.append(doc.getContent());
                contextBuilder.append("\n\n");
            }
            
            String context = contextBuilder.toString().trim();
            
            // 构建提示词
            String prompt = String.format("根据以下上下文信息回答问题。\n\n上下文:\n%s\n\n问题: %s", context, query);
            
            // 3. 使用Ollama生成回答
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "你是一个有用的助手，必须根据提供的上下文回答问题。"));
            messages.add(Map.of("role", "user", "content", prompt));
            
            // 生成回答
            String answer = ollamaClient.generateChatCompletion(model, messages);
            
            // 不再添加参考来源信息
            
            return answer;
        } catch (Exception e) {
            System.err.println("Error during RAG query: " + e.getMessage());
            return "处理查询时出错: " + e.getMessage();
        }
    }
    
    // 执行RAG查询，生成流式回答
    public CompletableFuture<Void> ragQueryStream(String query, OllamaClient.ResponseCallback callback) {
        try {
            // 1. 从向量存储中检索相关文档，增加获取的文档数量以确保覆盖多文件内容
            List<Document> relevantDocs = vectorStore.similaritySearch(query, 10);
            
            // 2. 构建提示，在每个文档块中嵌入来源信息，并收集实际相关的文档来源
            // 使用更严格的过滤和排序，确保只使用最相关的文档
            List<Document> filteredDocs = relevantDocs.stream()
                    .filter(doc -> {
                        // 检查文档嵌入向量是否存在且有效
                        return doc.getEmbedding() != null && !doc.getEmbedding().isEmpty() && 
                               doc.getContent() != null && !doc.getContent().trim().isEmpty();
                    })
                    .limit(5)  // 只使用最相关的前5个文档
                    .collect(Collectors.toList());
            
            // 构建上下文并收集实际使用的文档来源
            StringBuilder contextBuilder = new StringBuilder();
            Map<String, Document> sourceDocs = new LinkedHashMap<>();
            
            for (Document doc : filteredDocs) {
                String fileName = "未知来源";
                if (doc.getMetadata() != null && doc.getMetadata().containsKey("fileName")) {
                    fileName = (String) doc.getMetadata().get("fileName");
                    // 记录实际使用的来源文档
                    sourceDocs.put(fileName, doc);
                }
                contextBuilder.append("[SOURCE: " + fileName + "]\n");
                contextBuilder.append(doc.getContent());
                contextBuilder.append("\n\n");
            }
            
            String context = contextBuilder.toString().trim();
            
            // 构建提示词
            String prompt = String.format("根据以下上下文信息回答问题。\n\n上下文:\n%s\n\n问题: %s", context, query);
            
            // 使用Ollama生成流式回答
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "你是一个有用的助手，必须根据提供的上下文回答问题。"));
            messages.add(Map.of("role", "user", "content", prompt));
            
            // 创建包装后的回调函数，在回答完成后添加来源信息
            OllamaClient.ResponseCallback wrappedCallback = new OllamaClient.ResponseCallback() {
                @Override
                public void onResponse(String content) {
                    callback.onResponse(content);
                }
                
                @Override
                public void onComplete() {
                    // 不再添加参考来源信息
                    callback.onComplete();
                }
                
                @Override
                public void onError(Exception e) {
                    callback.onError(e);
                }
            };
            
            // 使用包装后的回调
            return ollamaClient.generateChatCompletionStream(model, messages, wrappedCallback);
        } catch (Exception e) {
            System.err.println("Error during RAG query stream: " + e.getMessage());
            callback.onError(e);
            return CompletableFuture.completedFuture(null);
        }
    }

    // 清除向量存储中的所有文档
    public void clearVectorStore() {
        vectorStore.deleteAll();
        fileMappings.clear();
    }
    
    // 保存文件ID和文件名的映射
    private void saveFileMapping(String fileId, String fileName) {
        fileMappings.put(fileId, fileName);
    }
    
    // 根据文件ID删除文件
    public boolean deleteFile(String fileId) {
        if (!fileMappings.containsKey(fileId)) {
            return false;
        }
        
        boolean deleted = vectorStore.deleteByFileId(fileId);
        if (deleted) {
            String fileName = fileMappings.remove(fileId);
            logger.info("删除文件成功: {}, 文件名: {}", fileId, fileName);
        }
        return deleted;
    }
    
    // 获取所有已上传的文件列表，包含详细信息
    public List<Map<String, String>> getAllFiles() {
        List<Map<String, String>> files = new ArrayList<>();
        
        // 使用SimpleVectorStore获取所有文件的详细信息
        Map<String, Map<String, Object>> fileDetailsMap = vectorStore.getAllFilesDetails();
        
        for (Map.Entry<String, Map<String, Object>> entry : fileDetailsMap.entrySet()) {
            String fileId = entry.getKey();
            Map<String, Object> fileDetails = entry.getValue();
            
            Map<String, String> fileInfo = new HashMap<>();
            fileInfo.put("id", fileId);
            
            // 添加文件名
            if (fileDetails.containsKey("fileName")) {
                fileInfo.put("name", fileDetails.get("fileName").toString());
            } else if (fileMappings.containsKey(fileId)) {
                fileInfo.put("name", fileMappings.get(fileId));
            }
            
            // 添加文件大小
            if (fileDetails.containsKey("fileSize")) {
                fileInfo.put("size", fileDetails.get("fileSize").toString());
            }
            
            // 添加文件类型（从文件名推断）
            String fileName = fileInfo.getOrDefault("name", "");
            if (!fileName.isEmpty()) {
                int lastDotIndex = fileName.lastIndexOf('.');
                if (lastDotIndex > 0) {
                    fileInfo.put("type", fileName.substring(lastDotIndex + 1).toUpperCase());
                } else {
                    fileInfo.put("type", "未知类型");
                }
            }
            
            // 添加上传时间
            if (fileDetails.containsKey("uploadedAt")) {
                fileInfo.put("uploadedAt", fileDetails.get("uploadedAt").toString());
            }
            
            files.add(fileInfo);
        }
        
        // 对于在fileMappings中但不在fileDetailsMap中的文件（可能是旧数据）
        for (Map.Entry<String, String> entry : fileMappings.entrySet()) {
            String fileId = entry.getKey();
            if (!fileDetailsMap.containsKey(fileId)) {
                Map<String, String> fileInfo = new HashMap<>();
                fileInfo.put("id", fileId);
                fileInfo.put("name", entry.getValue());
                fileInfo.put("size", "0");
                fileInfo.put("type", "未知类型");
                fileInfo.put("uploadedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date()));
                files.add(fileInfo);
            }
        }
        
        return files;
    }
    
    // 辅助方法：将文档分割成块（改进版，基于句子和段落进行智能分割）
    private List<String> splitDocumentIntoChunks(String content, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        
        // 首先按段落分割
        String[] paragraphs = content.split("\n\s*\n");
        
        StringBuilder currentChunk = new StringBuilder();
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;
            
            // 如果当前段落已经超过chunkSize，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先添加当前累积的内容
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                
                // 按句子分割段落
                List<String> sentences = splitIntoSentences(paragraph);
                for (String sentence : sentences) {
                    if (currentChunk.length() + sentence.length() + 1 > chunkSize) {
                        // 如果添加当前句子会超过chunkSize，保存当前chunk
                        chunks.add(currentChunk.toString());
                        currentChunk.setLength(0);
                        
                        // 处理超长句子（不太可能，但为了安全起见）
                        if (sentence.length() > chunkSize) {
                            // 对于超长句子，使用字符分割并确保overlap
                            for (int i = 0; i < sentence.length(); i += chunkSize - overlap) {
                                int end = Math.min(i + chunkSize, sentence.length());
                                chunks.add(sentence.substring(i, end));
                            }
                            continue;
                        }
                    }
                    if (currentChunk.length() > 0) {
                        currentChunk.append(" ");
                    }
                    currentChunk.append(sentence);
                }
                
                // 添加最后一个chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
            } else {
                // 如果添加当前段落不会超过chunkSize，添加到当前chunk
                if (currentChunk.length() + paragraph.length() + 2 <= chunkSize) {
                    if (currentChunk.length() > 0) {
                        currentChunk.append("\n\n");
                    }
                    currentChunk.append(paragraph);
                } else {
                    // 否则保存当前chunk并开始新的chunk
                    chunks.add(currentChunk.toString());
                    // 考虑overlap，从当前chunk末尾取overlap长度的内容
                    if (overlap > 0 && chunks.size() > 0) {
                        String lastChunk = chunks.get(chunks.size() - 1);
                        if (lastChunk.length() > overlap) {
                            currentChunk.append(lastChunk.substring(lastChunk.length() - overlap));
                            // 添加一个分隔符
                            if (currentChunk.length() > 0) {
                                currentChunk.append(" ");
                            }
                        } else {
                            currentChunk.append(lastChunk);
                            currentChunk.append(" ");
                        }
                    }
                    currentChunk.append(paragraph);
                }
            }
        }
        
        // 添加最后一个chunk（如果有）
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }
    
    // 辅助方法：将文本分割成句子
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        // 简单的句子分割正则表达式，匹配句号、问号、感叹号后跟空格或换行
        // 注意：这是一个简化版本，实际应用中可能需要更复杂的NLP处理
        String[] sentenceArray = text.split("(?<=[.!?])\\s+");
        for (String sentence : sentenceArray) {
            sentence = sentence.trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }
}