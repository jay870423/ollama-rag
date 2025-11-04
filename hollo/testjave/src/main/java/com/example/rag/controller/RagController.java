package com.example.rag.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.rag.service.RagService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 作者: liangyajie
 * 联系方式: 695274107@qq.com
 * RAG控制器，处理HTTP请求
 */

// 查询请求的DTO类
class QueryRequest {
    private String query;
    
    public String getQuery() {
        return query;
    }
    
    public void setQuery(String query) {
        this.query = query;
    }
}

@RestController
@CrossOrigin(origins = "*")  // 允许所有来源，生产环境应该限制具体域名
// 注意：由于应用配置了context-path=/api，所以这里不需要再加/api前缀
public class RagController {

    private final RagService ragService;

    @Autowired
    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    // 上传文档并添加到向量存储
    @PostMapping("/upload")
    public ResponseEntity<String> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        try {
            // 将MultipartFile转换为临时文件
            Path tempFilePath = Files.createTempFile("upload-", "-" + file.getOriginalFilename());
            File tempFile = tempFilePath.toFile();
            file.transferTo(tempFile);

            try {
                // 添加文档到向量存储
                ragService.addDocument(tempFile);
            } catch (IOException | org.apache.tika.exception.TikaException e) {
                throw new RuntimeException("处理文档时出错", e);
            } finally {
                // 确保临时文件被删除
                if (!tempFile.delete()) {
                    tempFile.deleteOnExit();
                }
            }
            return ResponseEntity.ok("文档上传成功");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("文件上传失败: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(e.getMessage());
        }
    }

    // 执行RAG查询（非流式）
    @PostMapping("/query")
    public ResponseEntity<String> query(@RequestBody QueryRequest request) {
        try {
            String answer = ragService.ragQuery(request.getQuery());
            return ResponseEntity.ok(answer);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("查询处理失败: " + e.getMessage());
        }
    }
    
    // 执行RAG查询（流式响应）
    @PostMapping("/query/stream")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter> queryStream(@RequestBody QueryRequest request) {
        try {
            // 创建响应体发射器，设置超时时间为5分钟（300000毫秒）
            org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter(300000L);
            
            // 设置回调处理流式响应
            ragService.ragQueryStream(request.getQuery(), new com.example.rag.client.OllamaClient.ResponseCallback() {
                @Override
                public void onResponse(String content) {
                    try {
                        // 发送数据
                        emitter.send("data: " + content + "\n\n");
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                }
                
                @Override
                public void onComplete() {
                    emitter.complete();
                }
                
                @Override
                public void onError(Exception e) {
                    emitter.completeWithError(e);
                }
            });
            
            // 设置HTTP头部
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            headers.setCacheControl("no-cache");
            headers.setConnection("keep-alive");
            
            return new ResponseEntity<>(emitter, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // 清除向量存储中的所有文档
    @DeleteMapping("/clear")
    public ResponseEntity<String> clearDocuments() {
        try {
            ragService.clearVectorStore();
            return ResponseEntity.ok("向量存储已清空");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("清空向量存储失败: " + e.getMessage());
        }
    }
    
    // 根据文件ID删除单个文件
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<String> deleteFile(@PathVariable String fileId) {
        try {
            boolean deleted = ragService.deleteFile(fileId);
            if (deleted) {
                return ResponseEntity.ok("文件删除成功");
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("删除文件失败: " + e.getMessage());
        }
    }
    
    // 获取所有已上传的文件列表
    @GetMapping("/files")
    public ResponseEntity<List<Map<String, String>>> getFiles() {
        try {
            List<Map<String, String>> files = ragService.getAllFiles();
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}