package com.example.rag.model;

import java.util.List;
import java.util.Map;

/**
 * 作者: liangyajie
 * 联系方式: 695274107@qq.com
 * 文档模型类，用于存储文档内容、元数据和嵌入向量
 */

public class Document {
    private String content;
    private Map<String, Object> metadata;
    private List<Double> embedding;

    public Document(String content, Map<String, Object> metadata) {
        this.content = content;
        this.metadata = metadata;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding;
    }
}