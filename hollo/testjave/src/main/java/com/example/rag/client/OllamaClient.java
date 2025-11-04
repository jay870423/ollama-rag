package com.example.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 作者: liangyajie
 * 联系方式: 695274107@qq.com
 * Ollama客户端，用于与本地Ollama模型交互
 */

@Component
public class OllamaClient {
    
    private final String ollamaBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 无参构造函数，默认使用localhost:11434
    public OllamaClient() {
        this("http://localhost:11434");
    }

    // 带参构造函数，支持自定义URL
    public OllamaClient(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl;
        // 创建使用连接池的HttpClient
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)) // 连接超时时间
                .version(HttpClient.Version.HTTP_2) // 使用HTTP/2
                .followRedirects(HttpClient.Redirect.NORMAL) // 正常重定向
                // 注意：Java 11的HttpClient不直接支持设置连接池大小
                // 这些参数由底层实现管理，可以通过JVM参数调整
                .build(); // 构建客户端，内部会使用连接池
        this.objectMapper = new ObjectMapper();
    }

    // 生成嵌入向量
    public List<Double> generateEmbedding(String model, String text) throws IOException, InterruptedException {
        String url = ollamaBaseUrl + "/api/embeddings";
        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", text                  // text 里可以任意字符
        );
        String requestBody = this.objectMapper.writeValueAsString(body);
        
        // 构建请求并设置响应超时时间
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(java.time.Duration.ofSeconds(60)) // 嵌入向量请求的响应超时时间
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        // 解析嵌入向量
        List<Double> embedding = new ArrayList<>();
        JsonNode embeddingNode = root.path("embedding");
        if (embeddingNode.isArray()) {
            for (JsonNode element : embeddingNode) {
                embedding.add(element.asDouble());
            }
        }
        return embedding;
    }

    // 生成聊天完成（非流式）
    public String generateChatCompletion(String model, List<Map<String, String>> messages) throws IOException, InterruptedException {
        String url = ollamaBaseUrl + "/api/chat";
        
        // 构建请求体
        Map<String, Object> requestData = Map.of(
                "model", model,
                "messages", messages,
                "stream", false
        );
        String requestBody = objectMapper.writeValueAsString(requestData);

        // 构建请求并设置响应超时时间
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        
        return root.path("message").path("content").asText();
    }
    
    // 生成聊天完成（流式）
    public CompletableFuture<Void> generateChatCompletionStream(
            String model, 
            List<Map<String, String>> messages, 
            ResponseCallback callback) throws IOException, InterruptedException {
        String url = ollamaBaseUrl + "/api/chat";
        
        // 构建请求体，设置stream=true
        Map<String, Object> requestData = Map.of(
                "model", model,
                "messages", messages,
                "stream", true
        );
        String requestBody = objectMapper.writeValueAsString(requestData);

        // 构建请求，设置更长的超时时间（5分钟）以避免流式请求超时
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream") // 明确指定接收流式响应
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(java.time.Duration.ofMinutes(5)) // 流式请求设置5分钟超时时间
                .build();

        // 发送请求并处理流式响应
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        // 确保响应状态码正确
                        if (response.statusCode() != 200) {
                            throw new IOException("Unexpected status code: " + response.statusCode());
                        }
                        
                        // 逐行处理响应
                        response.body().forEach(line -> {
                            try {
                                if (!line.isEmpty()) {
                                    // 解析每行JSON
                                    JsonNode root = objectMapper.readTree(line);
                                    
                                    // 提取content
                                    String content = root.path("message").path("content").asText();
                                    
                                    if (!content.isEmpty()) {
                                        // 立即发送内容，确保流式效果
                                        callback.onResponse(content);
                                    }
                                }
                            } catch (Exception e) {
                                // 记录错误但继续处理其他行
                                System.err.println("Error processing line: " + e.getMessage());
                            }
                        });
                        
                        // 通知完成
                        callback.onComplete();
                    } catch (Exception e) {
                        callback.onError(e);
                    }
                })
                .exceptionally(e -> {
                    // 处理异常情况
                    System.err.println("Stream request failed: " + e.getMessage());
                    callback.onError(new Exception("流式请求失败: " + e.getMessage(), e));
                    return null;
                });
    }
    
    // 响应回调接口
    public interface ResponseCallback {
        void onResponse(String content);
        void onComplete();
        void onError(Exception e);
    }
}